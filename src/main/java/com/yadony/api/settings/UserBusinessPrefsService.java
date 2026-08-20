package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CountryCatalog;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserBusinessPrefsService {

    private final UserBusinessPrefsRepository repository;
    private final UserRepository userRepository;
    private final CountryLockService countryLockService;
    private final CurrencyLockService currencyLockService;
    private final ActiveCurrencyResolver activeCurrencyResolver;

    public UserBusinessPrefsService(UserBusinessPrefsRepository repository,
                                    UserRepository userRepository,
                                    CountryLockService countryLockService,
                                    CurrencyLockService currencyLockService,
                                    ActiveCurrencyResolver activeCurrencyResolver) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.countryLockService = countryLockService;
        this.currencyLockService = currencyLockService;
        this.activeCurrencyResolver = activeCurrencyResolver;
    }

    @Transactional(readOnly = true)
    public UserBusinessPrefsDto getPrefs(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        UserEntity user = findUserOrThrow(userId);
        // La devise est desormais une donnee propre : la ligne existante fait foi
        // telle quelle. Tant qu'aucune ligne n'existe encore (compte tout juste
        // inscrit, jamais passe par upsert), on ne peut pas lire de valeur stockee :
        // le pays sert alors de valeur initiale derivee, via ActiveCurrencyResolver.
        UserBusinessPrefsDto dto = repository.findById(userId)
                .map(this::toDto)
                .orElseGet(() -> UserBusinessPrefsDto.defaults()
                        .withCurrencyCode(activeCurrencyResolver.resolve(userId)));
        return withLockStatus(dto, user);
    }

    @CacheEvict(value = "announcements-search", allEntries = true)
    public UserBusinessPrefsDto upsert(String firebaseUid, UserBusinessPrefsDto dto) {
        UUID userId = resolveUserId(firebaseUid);
        UserEntity user = findUserOrThrow(userId);
        Optional<UserBusinessPrefsEntity> existing = repository.findById(userId);
        UserBusinessPrefsEntity e = existing.orElseGet(() -> {
            UserBusinessPrefsEntity x = new UserBusinessPrefsEntity();
            x.setUserId(userId);
            return x;
        });

        // Gel au premier mouvement d'argent (lot 2, désormais porté par le pays) :
        // changer de pays n'est plus permis une fois un envoi engagé ou un
        // portefeuille entamé, sinon la devise dérivée d'un bid déjà en cours
        // divergerait silencieusement de ce compte.
        //
        // Aucune exemption « premier choix » sur `user.getCountry() == null` : V225 a
        // remis TOUS les pays à NULL, y compris ceux de comptes déjà porteurs d'un
        // compte Connect, d'un portefeuille alimenté ou d'un envoi engagé. Une telle
        // exemption leur aurait offert un changement de pays gratuit juste après le
        // déploiement, puis refermé le verrou sur le mauvais pays (celui d'un compte
        // Connect est immuable chez Stripe). Pour un utilisateur réellement neuf,
        // isLocked() rend false par construction : la garde ne coûte rien.
        String requestedCountry = dto.country();
        if (requestedCountry != null && !CountryCatalog.isSupported(requestedCountry)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country-unsupported", "Country Unsupported",
                    "Ce pays n'est pas encore desservi par yadony.");
        }
        boolean countryChanges = requestedCountry != null
                && !requestedCountry.equalsIgnoreCase(user.getCountry());
        if (countryChanges && countryLockService.isLocked(userId)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country-locked", "Country Locked",
                    "Impossible de changer de pays : un envoi est en cours, le "
                            + "portefeuille n'est pas vide, ou votre compte de "
                            + "paiement est deja cree.");
        }
        if (countryChanges) {
            user.setCountry(requestedCountry.toUpperCase(Locale.ROOT));
            userRepository.save(user);
        }

        e.setWeightUnit(dto.weightUnit());

        // Devise : donnee propre, gardee uniquement par le solde du portefeuille (lot
        // 3, CurrencyLockService) — independante du pays et de sa propre garde
        // country-locked ci-dessus. Un client qui n'envoie pas de devise (champ omis,
        // pas de contrainte @NotNull) laisse la devise existante intacte ; un compte
        // tout juste cree sans devise fournie recoit la valeur initiale derivee du
        // pays, exactement comme le fait getPrefs() pour une ligne encore inexistante.
        String requestedCurrency = dto.currencyCode();
        if (requestedCurrency != null) {
            SupportedCurrency validated = SupportedCurrency.fromCode(requestedCurrency);
            if (validated == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "currency-unsupported", "Currency Unsupported",
                        "Cette devise n'est pas prise en charge par yadony.");
            }
            String normalizedCurrency = validated.code().toUpperCase(Locale.ROOT);
            boolean currencyChanges = !normalizedCurrency.equalsIgnoreCase(e.getCurrencyCode());
            if (currencyChanges && currencyLockService.isLocked(userId)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "currency-locked", "Currency Locked",
                        "Impossible de changer de devise : le portefeuille n'est pas vide.");
            }
            e.setCurrencyCode(normalizedCurrency);
        } else if (existing.isEmpty()) {
            e.setCurrencyCode(activeCurrencyResolver.resolve(userId));
        }

        e.setPickupRadiusKm(dto.pickupRadiusKm());
        e.setDefaultPackageWeightKg(dto.defaultPackageWeightKg());
        e.setMinBidPriceEur(dto.minBidPriceEur());
        e.setContactMode(dto.contactMode());
        e.setResponseDelayHours(dto.responseDelayHours());
        return withLockStatus(toDto(repository.save(e)), user);
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user_not_found",
                        "User not found", "Utilisateur introuvable"));
    }

    private UserEntity findUserOrThrow(UUID userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user_not_found",
                        "User not found", "Utilisateur introuvable"));
    }

    private UserBusinessPrefsDto toDto(UserBusinessPrefsEntity e) {
        return new UserBusinessPrefsDto(
                e.getWeightUnit(),
                e.getCurrencyCode(),
                e.getPickupRadiusKm(),
                e.getDefaultPackageWeightKg(),
                e.getMinBidPriceEur(),
                e.getContactMode(),
                e.getResponseDelayHours(),
                null,
                null,
                null
        );
    }

    private UserBusinessPrefsDto withLockStatus(UserBusinessPrefsDto dto, UserEntity user) {
        boolean countryLocked = countryLockService.isLocked(user.getId());
        boolean currencyLocked = currencyLockService.isLocked(user.getId());
        return new UserBusinessPrefsDto(
                dto.weightUnit(),
                dto.currencyCode(),
                dto.pickupRadiusKm(),
                dto.defaultPackageWeightKg(),
                dto.minBidPriceEur(),
                dto.contactMode(),
                dto.responseDelayHours(),
                currencyLocked,
                user.getCountry(),
                countryLocked
        );
    }
}

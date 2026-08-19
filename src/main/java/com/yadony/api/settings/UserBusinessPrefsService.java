package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CountryCatalog;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class UserBusinessPrefsService {

    private final UserBusinessPrefsRepository repository;
    private final UserRepository userRepository;
    private final CountryLockService countryLockService;
    private final ActiveCurrencyResolver activeCurrencyResolver;

    public UserBusinessPrefsService(UserBusinessPrefsRepository repository,
                                    UserRepository userRepository,
                                    CountryLockService countryLockService,
                                    ActiveCurrencyResolver activeCurrencyResolver) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.countryLockService = countryLockService;
        this.activeCurrencyResolver = activeCurrencyResolver;
    }

    @Transactional(readOnly = true)
    public UserBusinessPrefsDto getPrefs(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        UserEntity user = findUserOrThrow(userId);
        UserBusinessPrefsDto dto = repository.findById(userId)
                .map(this::toDto)
                .orElse(UserBusinessPrefsDto.defaults());
        // La colonne currency_code n'est qu'un cache, reecrit uniquement dans upsert().
        // La rendre telle quelle ferait mentir les Reglages : apres V225 (pays remis a
        // NULL), un compte senegalais dont la colonne vaut encore XOF lirait « XOF »
        // pendant que tout le reste de l'application le traite en EUR, jusqu'au 422 au
        // moment d'encherir sur une annonce XOF. On derive donc a la lecture, exactement
        // comme a l'ecriture : devise lue et devise appliquee ne peuvent plus diverger.
        dto = dto.withCurrencyCode(activeCurrencyResolver.resolve(userId));
        return withLockStatus(dto, user);
    }

    @CacheEvict(value = "announcements-search", allEntries = true)
    public UserBusinessPrefsDto upsert(String firebaseUid, UserBusinessPrefsDto dto) {
        UUID userId = resolveUserId(firebaseUid);
        UserEntity user = findUserOrThrow(userId);
        java.util.Optional<UserBusinessPrefsEntity> existing = repository.findById(userId);
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
        // La devise n'est plus choisie : elle est recalculee depuis le pays et
        // stockee comme cache, les annonces et bids continuant de la lire ici.
        e.setCurrencyCode(activeCurrencyResolver.resolve(userId));
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
        boolean locked = countryLockService.isLocked(user.getId());
        return new UserBusinessPrefsDto(
                dto.weightUnit(),
                dto.currencyCode(),
                dto.pickupRadiusKm(),
                dto.defaultPackageWeightKg(),
                dto.minBidPriceEur(),
                dto.contactMode(),
                dto.responseDelayHours(),
                locked,
                user.getCountry(),
                locked
        );
    }
}

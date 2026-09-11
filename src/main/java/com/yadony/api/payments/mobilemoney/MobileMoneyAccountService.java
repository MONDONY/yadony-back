package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAccountResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyProvidersResponse;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compte de versement mobile money : numéro fourni par l'appelant TOUJOURS prioritaire s'il
 * est renseigné (il peut légitimement différer du numéro du compte Firebase — numéro d'un
 * proche, opérateur distinct…), sinon numéro du compte Firebase (déjà vérifié par OTP).
 * Opérateur prédit par pawaPay, devise contrôlée contre la devise active. Risque accepté :
 * un numéro fourni n'est pas vérifié par OTP, une session compromise pourrait donc rediriger
 * les versements futurs — voir la javadoc de {@link #activate} pour les garde-fous en place.
 */
@Service
public class MobileMoneyAccountService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyAccountService.class);

    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final PawapayProviderResolver providers;
    private final ActiveCurrencyResolver currencyResolver;
    private final AuditService audit;
    private final PawapayProperties props;

    public MobileMoneyAccountService(UserRepository userRepository, FirebaseContactService firebaseContact,
                                     PawapayProviderResolver providers, ActiveCurrencyResolver currencyResolver,
                                     AuditService audit, PawapayProperties props) {
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.providers = providers;
        this.currencyResolver = currencyResolver;
        this.audit = audit;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public MobileMoneyAccountResponse get(UUID userId) {
        return toResponse(userRepository.findById(userId).orElseThrow(() -> notFound(userId)));
    }

    /** Ancien contrat (client sans liste de réseaux) : équivaut à {@code activate(userId, providedPhone, null)}. */
    @Transactional
    public MobileMoneyAccountResponse activate(UUID userId, String providedPhone) {
        return activate(userId, providedPhone, null);
    }

    /**
     * Active le versement mobile money. Le numéro FOURNI par l'appelant est TOUJOURS prioritaire
     * dès qu'il est renseigné (normalisé par {@link Msisdn#normalize}, 422 {@code mobile-money-invalid-phone}
     * s'il est invalide), même si le compte Firebase a déjà un téléphone : il peut légitimement
     * différer. Sans numéro fourni, le téléphone Firebase (vérifié par OTP) ; sans aucun numéro,
     * 422 {@code mobile-money-phone-required}.
     *
     * <p>Réseaux : {@code providers} vide ou nul, l'opérateur prédit est seul accepté (ancien
     * contrat). Sinon chaque code doit figurer dans le catalogue du numéro pour la devise active
     * (422 {@code mobile-money-account-unsupported} nommant le réseau), la liste est enregistrée
     * dans l'ordre du catalogue, et {@code mobile_money_provider} vaut le détecté s'il est coché,
     * sinon le premier coché.
     *
     * <p><b>Risque accepté</b> : un numéro fourni n'est PAS vérifié par OTP, une session compromise
     * pourrait rediriger les versements futurs. Garde-fous : provenance tracée dans l'audit
     * ({@code source}), numéro jamais rendu qu'en forme masquée. À renforcer par OTP dès que la
     * vérification par SMS sera disponible.
     */
    @Transactional
    public MobileMoneyAccountResponse activate(UUID userId, String providedPhone, List<String> providers) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        // Verrou pessimiste : sans lui, deux activations concurrentes pourraient toutes
        // deux lire l'état initial et écrire deux fois (règle projet #17).
        UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        PhoneSource phone = resolvePhone(user, providedPhone, false);
        if ("provided".equals(phone.source())) {
            log.info("Activation mobile money pour {} avec un numéro saisi (peut différer du numéro Firebase)", userId);
        }
        String active = currencyResolver.resolve(userId);
        List<String> wanted = cleanCodes(providers);
        if (wanted.isEmpty()) {
            PawapayProviderResolver.Resolved resolved;
            try {
                resolved = this.providers.resolve(phone.phone(), PawapayOperationKind.PAYOUT, active,
                        "l'activation mobile money de " + userId);
            } catch (PawapayProviderResolver.UnsupportedNumberException e) {
                throw unsupported(userId, reasonDetail(e, active));
            }
            apply(user, resolved.msisdn(), resolved.countryAlpha2(), resolved.config().currency(),
                    resolved.provider(), List.of(resolved.provider()));
        } else {
            PawapayProviderResolver.Catalogue catalogue = catalogueOrUnsupported(userId, phone.phone(), active);
            List<String> accepted = selectAccepted(userId, catalogue, wanted);
            apply(user, catalogue.msisdn(), catalogue.countryAlpha2(), catalogue.currency(),
                    fallbackProvider(catalogue, accepted), accepted);
        }
        userRepository.save(user);
        // Payload d'audit : masqué + source + réseaux, jamais le numéro en clair.
        audit.log("USER", userId, "MM_ACCOUNT_ACTIVATED", userId,
                Map.of("provider", user.getMobileMoneyProvider(), "providers", user.getMobileMoneyProviders(),
                        "msisdnMasked", user.getMobileMoneyMsisdnMasked(),
                        "currency", user.getMobileMoneyCurrency(), "source", phone.source()));
        return toResponse(user);
    }

    /**
     * Désactive le versement. Suppression LOGIQUE uniquement : le numéro et les
     * métadonnées restent en base pour une réactivation en un geste, seul le statut change.
     */
    @Transactional
    public MobileMoneyAccountResponse disable(UUID userId) {
        UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.DISABLED);
        userRepository.save(user);
        audit.log("USER", userId, "MM_ACCOUNT_DISABLED", userId, Map.of());
        return toResponse(user);
    }

    /**
     * Réseaux utilisables pour le versement sur un numéro : fourni, sinon déjà enregistré, sinon
     * Firebase (422 {@code mobile-money-phone-required} sans aucun). Lecture seule, rien n'est écrit.
     */
    @Transactional(readOnly = true)
    public MobileMoneyProvidersResponse providers(UUID userId, String providedPhone) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
        PhoneSource phone = resolvePhone(user, providedPhone, true);
        String active = currencyResolver.resolve(userId);
        return toProvidersResponse(catalogueOrUnsupported(userId, phone.phone(), active));
    }

    /**
     * Remplace les réseaux acceptés sans ressaisir le numéro. Compte {@code ACTIVE} obligatoire,
     * liste non vide, chaque code dans le catalogue du numéro enregistré. Le statut ne change pas.
     */
    @Transactional
    public MobileMoneyAccountResponse updateProviders(UUID userId, List<String> providers) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        if (user.getMobileMoneyStatus() != MobileMoneyPayoutStatus.ACTIVE
                || user.getMobileMoneyMsisdn() == null || user.getMobileMoneyMsisdn().isBlank()) {
            throw unsupported(userId, "Activez d'abord le versement mobile money.");
        }
        List<String> wanted = cleanCodes(providers);
        if (wanted.isEmpty()) {
            throw unsupported(userId, "Choisissez au moins un réseau.");
        }
        String active = currencyResolver.resolve(userId);
        PawapayProviderResolver.Catalogue catalogue = catalogueOrUnsupported(userId, user.getMobileMoneyMsisdn(), active);
        List<String> accepted = selectAccepted(userId, catalogue, wanted);
        user.setMobileMoneyProviderList(accepted);
        user.setMobileMoneyProvider(fallbackProvider(catalogue, accepted));
        userRepository.save(user);
        audit.log("USER", userId, "MM_ACCOUNT_PROVIDERS_UPDATED", userId,
                Map.of("providers", user.getMobileMoneyProviders(), "provider", user.getMobileMoneyProvider()));
        return toResponse(user);
    }

    static MobileMoneyProvidersResponse toProvidersResponse(PawapayProviderResolver.Catalogue c) {
        List<MobileMoneyProvidersResponse.ProviderOption> options = c.options().stream()
                .map(o -> new MobileMoneyProvidersResponse.ProviderOption(o.provider(), PawapayProviders.label(o.provider()),
                        o.provider().equalsIgnoreCase(c.detected())))
                .toList();
        return new MobileMoneyProvidersResponse(c.countryAlpha2(), c.currency(), Msisdn.mask(c.msisdn()), c.detected(), options);
    }

    /** Numéro retenu et sa provenance ({@code provided}, {@code stored}, {@code firebase}) pour l'audit. */
    record PhoneSource(String phone, String source) {}

    /**
     * Numéro fourni (normalisé, 422 {@code mobile-money-invalid-phone}), sinon numéro déjà enregistré
     * si {@code allowStored}, sinon téléphone Firebase, sinon 422 {@code mobile-money-phone-required}.
     * Le numéro fourni prime sans condition : le compte Firebase n'est même pas consulté.
     */
    private PhoneSource resolvePhone(UserEntity user, String providedPhone, boolean allowStored) {
        if (providedPhone != null && !providedPhone.isBlank()) {
            try {
                return new PhoneSource(Msisdn.normalize(providedPhone), "provided");
            } catch (IllegalArgumentException e) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-invalid-phone",
                        "Mobile Money Invalid Phone", "Numéro de téléphone invalide pour le versement mobile money.");
            }
        }
        if (allowStored && user.getMobileMoneyMsisdn() != null && !user.getMobileMoneyMsisdn().isBlank()) {
            return new PhoneSource(user.getMobileMoneyMsisdn(), "stored");
        }
        String firebasePhone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
        if (firebasePhone == null || firebasePhone.isBlank()) {
            log.warn("Numéro mobile money introuvable pour {} : aucun numéro vérifié ni saisi", user.getId());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Indiquez le numéro mobile money qui recevra vos versements.");
        }
        return new PhoneSource(firebasePhone, "firebase");
    }

    /** Libellé métier d'un numéro reconnu mais inexploitable pour le versement. */
    private static String reasonDetail(PawapayProviderResolver.UnsupportedNumberException e, String activeCurrency) {
        return switch (e.reason()) {
            case NO_PROVIDER -> "Aucun opérateur mobile money reconnu pour votre numéro.";
            case OPERATION_CLOSED -> e.providerLabel() + " ne permet pas encore le versement.";
            case CURRENCY_MISMATCH -> "Votre portefeuille est en " + activeCurrency + ", ce numéro reçoit du " + e.providerCurrency() + ".";
            case COUNTRY_UNKNOWN -> "Pays non reconnu pour ce numéro.";
            case PROVIDER_NOT_AVAILABLE -> "Réseau " + e.providerLabel() + " indisponible pour ce numéro.";
        };
    }

    private PawapayProviderResolver.Catalogue catalogueOrUnsupported(UUID userId, String phone, String activeCurrency) {
        try {
            return providers.catalogue(phone, PawapayOperationKind.PAYOUT, activeCurrency,
                    "le catalogue mobile money de " + userId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw unsupported(userId, reasonDetail(e, activeCurrency));
        }
    }

    /** Codes en majuscules, sans blancs ni doublons ni entrées vides. */
    static List<String> cleanCodes(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        return requested.stream().filter(Objects::nonNull).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** Vérifie chaque code contre le catalogue et rend les acceptés dans l'ordre du catalogue. */
    private static List<String> selectAccepted(UUID userId, PawapayProviderResolver.Catalogue catalogue, List<String> wanted) {
        // Revue finale, point 3 (Minor 2) : un code mal formé (retour à la ligne, ponctuation...)
        // ne doit jamais être échoué tel quel dans un detail ou un log. Validé AVANT toute
        // recherche dans le catalogue, avec un message qui ne le répète jamais.
        for (String code : wanted) {
            if (!PawapayProviders.isWellFormed(code)) {
                throw unsupported(userId, "Code de réseau invalide.");
            }
        }
        List<String> available = catalogue.options().stream().map(PawapayProviderConfig::provider).toList();
        for (String code : wanted) {
            if (!available.contains(code)) {
                throw unsupported(userId, "Réseau " + PawapayProviders.label(code) + " indisponible pour ce numéro.");
            }
        }
        return available.stream().filter(wanted::contains).toList();
    }

    /** Réseau de repli du versement : le détecté s'il est coché, sinon le premier coché. */
    private static String fallbackProvider(PawapayProviderResolver.Catalogue catalogue, List<String> accepted) {
        return catalogue.detected() != null && accepted.contains(catalogue.detected()) ? catalogue.detected() : accepted.get(0);
    }

    private static void apply(UserEntity user, String msisdn, String country, String currency,
                              String fallbackProvider, List<String> accepted) {
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyMsisdn(msisdn);
        user.setMobileMoneyMsisdnMasked(Msisdn.mask(msisdn));
        user.setMobileMoneyProvider(fallbackProvider);
        user.setMobileMoneyProviderList(accepted);
        user.setMobileMoneyCountry(country);
        user.setMobileMoneyCurrency(currency.toUpperCase(Locale.ROOT));
        user.setMobileMoneyVerifiedAt(Instant.now());
    }

    static MobileMoneyAccountResponse toResponse(UserEntity u) {
        List<MobileMoneyAccountResponse.ProviderView> providers = MobileMoneyNetworks.acceptedCodes(u).stream()
                .map(code -> new MobileMoneyAccountResponse.ProviderView(code, PawapayProviders.label(code))).toList();
        return new MobileMoneyAccountResponse(u.getMobileMoneyStatus().name(), u.getMobileMoneyMsisdnMasked(),
                u.getMobileMoneyProvider(), u.getMobileMoneyProvider() == null ? null : PawapayProviders.label(u.getMobileMoneyProvider()),
                u.getMobileMoneyCountry(), u.getMobileMoneyCurrency(), u.getMobileMoneyVerifiedAt(), providers);
    }

    /** Rejet métier (422) : trace la branche d'échec pour le support, sans jamais loguer le numéro. */
    private static YadonyBusinessException unsupported(UUID userId, String detail) {
        log.warn("Activation mobile money refusée pour {} : {}", userId, detail);
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-account-unsupported",
                "Mobile Money Account Unsupported", detail);
    }

    private static YadonyBusinessException notFound(UUID userId) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                "Utilisateur introuvable : " + userId);
    }
}

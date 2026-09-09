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
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compte de versement mobile money : numéro du compte Firebase de l'appelant (déjà vérifié
 * par OTP), toujours prioritaire, opérateur prédit par pawaPay, devise contrôlée contre la
 * devise active. Une saisie libre n'est tolérée qu'en l'absence de numéro Firebase — voir la
 * javadoc de {@link #activate} pour le motif et les garanties.
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

    /**
     * Active le versement mobile money. Le numéro du compte Firebase de l'appelant (déjà
     * vérifié par OTP) est TOUJOURS prioritaire dès qu'il existe : {@code providedPhone} est
     * alors ignoré en silence, sans erreur. Il n'est lu que si le compte Firebase n'a aucun
     * téléphone — saisie tolérée uniquement dans ce cas précis, tant que la vérification par
     * SMS (Twilio) n'est pas disponible pour ces comptes, faute de quoi ils n'auraient aucun
     * moyen d'activer le rail. Un numéro ainsi saisi n'est PAS vérifié par OTP : le risque
     * (versement dirigé vers un mauvais numéro) est documenté et assumé par le produit, tracé
     * dans l'audit ({@code source: "provided"} vs {@code "firebase"}). Sans numéro d'aucune
     * source, l'activation échoue en 422 {@code mobile-money-phone-required}.
     */
    @Transactional
    public MobileMoneyAccountResponse activate(UUID userId, String providedPhone) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        // Verrou pessimiste : sans lui, deux activations concurrentes pourraient toutes
        // deux lire l'état initial et écrire deux fois (règle projet #17).
        UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        String firebasePhone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
        String phone;
        String source;
        if (firebasePhone != null && !firebasePhone.isBlank()) {
            phone = firebasePhone;
            source = "firebase";
        } else if (providedPhone != null && !providedPhone.isBlank()) {
            // Erreur de saisie CLIENT (comme BidService#createBid, le même motif de
            // normalisation d'un numéro saisi) — contrairement au numéro prédit par pawaPay
            // plus bas, qui devient un 502 s'il est hors bornes : ici c'est l'utilisateur qui
            // peut corriger sa saisie, donc 422, jamais un 500 générique.
            try {
                phone = Msisdn.normalize(providedPhone);
            } catch (IllegalArgumentException e) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-invalid-phone",
                        "Mobile Money Invalid Phone", "Numéro de téléphone invalide pour le versement mobile money.");
            }
            source = "provided";
            log.info("Activation mobile money pour {} avec un numéro saisi (aucun téléphone Firebase)", userId);
        } else {
            log.warn("Activation mobile money refusée pour {} : aucun numéro vérifié ni saisi", userId);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Indiquez le numéro mobile money qui recevra vos versements.");
        }
        // Le compte reçoit dans la devise active du voyageur : c'est elle que l'opérateur prédit
        // doit servir. Une panne pawaPay remonte en 502 normalisé du rail (sinon un 500 générique
        // laisserait la ligne users verrouillée jusqu'au timeout HTTP) ; un numéro reconnu mais
        // inexploitable devient le 422 métier de l'activation, avec ses libellés.
        String active = currencyResolver.resolve(userId);
        PawapayProviderResolver.Resolved resolved;
        try {
            resolved = providers.resolve(phone, PawapayOperationKind.PAYOUT, active,
                    "l'activation mobile money de " + userId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw unsupported(userId, switch (e.reason()) {
                case NO_PROVIDER -> "Aucun opérateur mobile money reconnu pour votre numéro.";
                case OPERATION_CLOSED -> e.providerLabel() + " ne permet pas encore le versement.";
                case CURRENCY_MISMATCH -> "Votre portefeuille est en " + active + ", ce numéro reçoit du " + e.providerCurrency() + ".";
                case COUNTRY_UNKNOWN -> "Pays non reconnu pour ce numéro.";
            });
        }
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyMsisdn(resolved.msisdn());
        user.setMobileMoneyMsisdnMasked(Msisdn.mask(resolved.msisdn()));
        user.setMobileMoneyProvider(resolved.provider());
        user.setMobileMoneyCountry(resolved.countryAlpha2());
        user.setMobileMoneyCurrency(resolved.config().currency().toUpperCase(Locale.ROOT));
        user.setMobileMoneyVerifiedAt(Instant.now());
        userRepository.save(user);
        // Le payload d'audit ne porte que le masqué (+ la source, "firebase"/"provided", jamais
        // le numéro en clair) : AuditService le rédigerait de toute façon (clé "msisdnMasked" ne
        // matche pas le denylist "phone", donc conservé tel quel volontairement — c'est déjà la
        // forme publique, pas une PII en clair).
        audit.log("USER", userId, "MM_ACCOUNT_ACTIVATED", userId,
                Map.of("provider", resolved.provider(), "msisdnMasked", user.getMobileMoneyMsisdnMasked(),
                        "currency", user.getMobileMoneyCurrency(), "source", source));
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

    static MobileMoneyAccountResponse toResponse(UserEntity u) {
        return new MobileMoneyAccountResponse(u.getMobileMoneyStatus().name(), u.getMobileMoneyMsisdnMasked(),
                u.getMobileMoneyProvider(), u.getMobileMoneyProvider() == null ? null : PawapayProviders.label(u.getMobileMoneyProvider()),
                u.getMobileMoneyCountry(), u.getMobileMoneyCurrency(), u.getMobileMoneyVerifiedAt());
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

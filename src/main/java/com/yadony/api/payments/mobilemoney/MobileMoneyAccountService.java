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
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayCountries;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * Compte de versement mobile money : snapshot du téléphone Firebase (déjà vérifié par OTP),
 * opérateur prédit par pawaPay, devise contrôlée contre la devise active. Aucune saisie
 * libre : un mauvais numéro de versement est de l'argent perdu.
 */
@Service
public class MobileMoneyAccountService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyAccountService.class);

    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final PawapayClient client;
    private final ActiveCurrencyResolver currencyResolver;
    private final AuditService audit;
    private final PawapayProperties props;

    public MobileMoneyAccountService(UserRepository userRepository, FirebaseContactService firebaseContact,
                                     PawapayClient client, ActiveCurrencyResolver currencyResolver,
                                     AuditService audit, PawapayProperties props) {
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.client = client;
        this.currencyResolver = currencyResolver;
        this.audit = audit;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public MobileMoneyAccountResponse get(UUID userId) {
        return toResponse(userRepository.findById(userId).orElseThrow(() -> notFound(userId)));
    }

    /**
     * Active le versement mobile money. Le numéro n'est JAMAIS reçu en paramètre :
     * il est relu chez Firebase à partir de l'UID du compte, seule source de vérité pour
     * un téléphone déjà vérifié par OTP. Un numéro saisi librement ouvrirait la voie à un
     * détournement de tous les versements futurs dès qu'un compte serait compromis.
     */
    @Transactional
    public MobileMoneyAccountResponse activate(UUID userId) {
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        // Verrou pessimiste : sans lui, deux activations concurrentes pourraient toutes
        // deux lire l'état initial et écrire deux fois (règle projet #17).
        UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        String phone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("Activation mobile money refusée pour {} : aucun numéro vérifié chez Firebase", userId);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Ajoutez un numéro de téléphone vérifié à votre compte.");
        }
        // predictProvider et activeConfiguration sont deux appels HTTP pawaPay : une panne
        // réseau ou un 5xx y lève RestClientException — 502 normalisé du rail, sinon un 500
        // générique laisserait la ligne users verrouillée jusqu'au timeout HTTP.
        String context = "l'activation mobile money de " + userId;
        Optional<PawapayProviderPrediction> predicted;
        try {
            predicted = client.predictProvider(phone);
        } catch (RestClientException e) {
            throw PawapayErrors.providerUnavailable("predict-provider", context, e);
        }
        PawapayProviderPrediction prediction = predicted.orElse(null);
        if (prediction == null) {
            throw unsupported(userId, "Aucun opérateur mobile money reconnu pour votre numéro.");
        }
        Map<String, PawapayProviderConfig> configuration;
        try {
            configuration = client.activeConfiguration();
        } catch (RestClientException e) {
            throw PawapayErrors.providerUnavailable("active-configuration", context, e);
        }
        PawapayProviderConfig conf = configuration.get(prediction.provider());
        if (conf == null || !conf.supportsPayout()) {
            throw unsupported(userId, PawapayProviders.label(prediction.provider()) + " ne permet pas encore le versement.");
        }
        String active = currencyResolver.resolve(userId);
        if (!conf.currency().equalsIgnoreCase(active)) {
            throw unsupported(userId, "Votre portefeuille est en " + active + ", ce numéro reçoit du " + conf.currency() + ".");
        }
        // Même famille que les deux appels ci-dessus : un numéro prédit hors bornes n'est pas
        // une erreur de saisie utilisateur (il n'a rien saisi), c'est pawaPay qui répond une
        // donnée inexploitable — même 502, pas un 422 métier.
        String msisdn;
        try {
            msisdn = Msisdn.normalize(prediction.phoneNumber() != null ? prediction.phoneNumber() : phone);
        } catch (IllegalArgumentException e) {
            throw PawapayErrors.providerUnavailable("msisdn-normalize", context, e);
        }
        // PawapayCountries.toAlpha2 rend null pour un alpha-3 non couvert par la table ISO du
        // JDK : sans cette garde, la valeur nulle serait acceptée en silence ici
        // (users.mobile_money_country est nullable) et l'échec reporté au versement
        // (pawapay_operations.country est NOT NULL), en 500 générique et sans alerte — sur le
        // chemin qui engage l'argent. Même garde que MobileMoneyBidPaymentService#initiateDeposit.
        String country = PawapayCountries.toAlpha2(prediction.countryAlpha3());
        if (country == null) {
            throw unsupported(userId, "Pays non reconnu pour ce numéro.");
        }
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyMsisdn(msisdn);
        user.setMobileMoneyMsisdnMasked(Msisdn.mask(msisdn));
        user.setMobileMoneyProvider(prediction.provider());
        user.setMobileMoneyCountry(country);
        user.setMobileMoneyCurrency(conf.currency().toUpperCase(Locale.ROOT));
        user.setMobileMoneyVerifiedAt(Instant.now());
        userRepository.save(user);
        // Le payload d'audit ne porte que le masqué : AuditService le rédigerait de toute
        // façon (clé "msisdnMasked" ne matche pas le denylist "phone", donc conservé tel quel
        // volontairement — c'est déjà la forme publique, pas une PII en clair).
        audit.log("USER", userId, "MM_ACCOUNT_ACTIVATED", userId,
                Map.of("provider", prediction.provider(), "msisdnMasked", user.getMobileMoneyMsisdnMasked(),
                        "currency", user.getMobileMoneyCurrency()));
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

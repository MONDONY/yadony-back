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
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compte de versement mobile money : snapshot du téléphone Firebase (déjà vérifié par OTP),
 * opérateur prédit par pawaPay, devise contrôlée contre la devise active. Aucune saisie
 * libre : un mauvais numéro de versement est de l'argent perdu.
 */
@Service
public class MobileMoneyAccountService {

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
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-disabled",
                    "Mobile Money Disabled", "Le mobile money n'est pas encore disponible.");
        }
        // Verrou pessimiste : sans lui, deux activations concurrentes pourraient toutes
        // deux lire l'état initial et écrire deux fois (règle projet #17).
        UserEntity user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        String phone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
        if (phone == null || phone.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Ajoutez un numéro de téléphone vérifié à votre compte.");
        }
        PawapayProviderPrediction prediction = client.predictProvider(phone)
                .orElseThrow(() -> unsupported("Aucun opérateur mobile money reconnu pour votre numéro."));
        PawapayProviderConfig conf = client.activeConfiguration().get(prediction.provider());
        if (conf == null || !conf.supportsPayout()) {
            throw unsupported(PawapayProviders.label(prediction.provider()) + " ne permet pas encore le versement.");
        }
        String active = currencyResolver.resolve(userId);
        if (!conf.currency().equalsIgnoreCase(active)) {
            throw unsupported("Votre portefeuille est en " + active + ", ce numéro reçoit du " + conf.currency() + ".");
        }
        String msisdn = Msisdn.normalize(prediction.phoneNumber() != null ? prediction.phoneNumber() : phone);
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        user.setMobileMoneyMsisdn(msisdn);
        user.setMobileMoneyMsisdnMasked(Msisdn.mask(msisdn));
        user.setMobileMoneyProvider(prediction.provider());
        user.setMobileMoneyCountry(PawapayCountries.toAlpha2(prediction.countryAlpha3()));
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

    private static YadonyBusinessException unsupported(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-account-unsupported",
                "Mobile Money Account Unsupported", detail);
    }

    private static YadonyBusinessException notFound(UUID userId) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                "Utilisateur introuvable : " + userId);
    }
}

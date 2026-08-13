package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.notifications.SmsService;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class SmsOtpService {

    private static final Logger log = LoggerFactory.getLogger(SmsOtpService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();


    private final SmsOtpRepository smsOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsService smsService;
    private final SmsOtpProperties properties;
    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final AuditService auditService;
    private final boolean devProfile;

    public SmsOtpService(SmsOtpRepository smsOtpRepository,
                          PasswordEncoder passwordEncoder,
                          SmsService smsService,
                          SmsOtpProperties properties,
                          @Autowired(required = false) FirebaseAuth firebaseAuth,
                          UserRepository userRepository,
                          FirebaseContactService firebaseContact,
                          AuditService auditService,
                          Environment environment) {
        this.smsOtpRepository = smsOtpRepository;
        this.passwordEncoder  = passwordEncoder;
        this.smsService       = smsService;
        this.properties       = properties;
        this.firebaseAuth     = firebaseAuth;
        this.userRepository   = userRepository;
        this.firebaseContact  = firebaseContact;
        this.auditService     = auditService;
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        this.devProfile = !activeProfiles.contains("prod");
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Instant sendOtp(String phoneNumber) {
        // En dev/test, SMS désactivé est le réglage normal (voir le relais de log
        // plus bas) : on ne bloque qu'en prod, où un flag resté à false alors que
        // l'écran est accessible (build client périgé, deep link) enverrait
        // sinon un "code envoyé" silencieux qui n'arrive jamais.
        if (!smsService.isEnabled() && !devProfile) {
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-disabled",
                    "SMS OTP Disabled", "L'authentification par SMS n'est pas encore disponible");
        }

        int windowMinutes = properties.getRateWindowMinutes();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(windowMinutes);
        if (smsOtpRepository.countByPhoneSince(phoneNumber, since) >= properties.getMaxSendsPerWindow()) {
            throw new YadonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "phone-otp-rate-limit",
                    "Too Many Requests",
                    "Trop de codes demandés, réessaie dans " + windowMinutes + " min");
        }

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(properties.getOtpValidMinutes());

        SmsOtpEntity entity = new SmsOtpEntity();
        entity.setPhoneNumber(phoneNumber);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(expiresAt);
        smsOtpRepository.save(entity);

        smsService.send(phoneNumber, String.format(properties.getOtpTemplate(), code));
        // Le SMS réel est désactivé par défaut en dev (app.sms.enabled=false) et
        // SmsService caviarde volontairement le message dans ses logs — sans ce
        // relais, aucun moyen de connaître le code en local pour tester le flow.
        if (!smsService.isEnabled() && devProfile) {
            log.warn("📱 [DEV] Code OTP pour {} : {}", maskPhone(phoneNumber), code);
        }

        return expiresAt.toInstant(ZoneOffset.UTC);
    }

    public String verifyOtp(String phoneNumber, String code) {
        log.info("verifyOtp: phone='{}'", maskPhone(phoneNumber));
        consumeOtp(phoneNumber, code);

        if (firebaseAuth == null) {
            log.warn("FirebaseAuth not available — returning null custom token (test mode)");
            return null;
        }
        String uid = resolveOrCreateFirebaseUid(phoneNumber);
        try {
            return firebaseAuth.createCustomToken(uid, Map.of("otp_channel", "sms"));
        } catch (FirebaseAuthException e) {
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                    "Firebase Error", "Erreur lors de la création du token");
        }
    }

    /**
     * Résout l'UID Firebase déjà attribué à ce numéro (Phone Auth natif ou un
     * précédent passage par ce flow), n'en crée un nouveau qu'en dernier
     * recours. Un utilisateur déjà inscrit via le SDK natif a DÉJÀ un UID
     * Firebase rattaché à son numéro : inventer un UID différent (comme
     * EmailOtpService le fait avec {@code uid = email}) romprait
     * UserLinkerService.resolveAndLink() pour tout utilisateur pré-migration —
     * findByFirebaseUid() ne le retrouverait plus, créant un compte fantôme.
     */
    private String resolveOrCreateFirebaseUid(String phoneNumber) {
        try {
            return firebaseAuth.getUserByPhoneNumber(phoneNumber).getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
                throw new YadonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                        "Firebase Error", "Erreur lors de la résolution du compte");
            }
        }
        try {
            return firebaseAuth.createUser(new UserRecord.CreateRequest()
                    .setPhoneNumber(phoneNumber)).getUid();
        } catch (FirebaseAuthException e) {
            // Course possible : deux vérifications concurrentes pour un numéro tout
            // neuf (double resend, deux appareils). Le second createUser échoue,
            // on relit alors l'UID gagnant plutôt que d'échouer la requête.
            if (e.getAuthErrorCode() == AuthErrorCode.PHONE_NUMBER_ALREADY_EXISTS) {
                try {
                    return firebaseAuth.getUserByPhoneNumber(phoneNumber).getUid();
                } catch (FirebaseAuthException e2) {
                    throw new YadonyBusinessException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                            "Firebase Error", "Erreur lors de la résolution du compte");
                }
            }
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                    "Firebase Error", "Erreur lors de la création du compte");
        }
    }

    /**
     * Rattache un numéro au compte authentifié, après avoir consommé le code OTP
     * dans la même opération : la preuve de possession est donc intrinsèque, un
     * appelant ne peut pas s'attribuer le numéro d'un tiers.
     *
     * <p>Ajout seulement, jamais remplacement : le numéro identifie le compte
     * Firebase. Un compte qui en porte déjà un est refusé (409).
     */
    public void attachPhoneToAccount(String firebaseUid, String phoneNumber, String code) {
        log.info("attachPhoneToAccount: uid={} phone='{}'", firebaseUid, maskPhone(phoneNumber));

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        // Preuve de possession avant toute écriture : le code est consommé ici.
        consumeOtp(phoneNumber, code);

        if (firebaseContact.getContact(firebaseUid).phoneNumber() != null) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "phone-already-set",
                    "Phone Already Set",
                    "Un numéro est déjà associé à ce compte et ne peut pas être remplacé");
        }

        if (firebaseContact.isPhoneTakenByAnother(phoneNumber, firebaseUid)) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "phone-already-exists",
                    "Phone Number Already Registered", "Ce numéro est déjà associé à un compte");
        }

        firebaseContact.updatePhone(firebaseUid, phoneNumber);

        // Payload sans PII : le numéro lui-même ne doit pas atterrir dans audit_log.
        auditService.log("USER", user.getId(), "USER_PHONE_ATTACHED", user.getId(),
                Map.of("verifiedBy", "sms-otp"));
    }

    /**
     * Valide le code reçu pour ce numéro et le consomme (usage unique).
     * Lève si le code est absent, expiré, faux, ou si le budget de tentatives est épuisé.
     */
    void consumeOtp(String phoneNumber, String code) {

        // Budget global par numéro : sans lui, chaque renvoi de code offrirait
        // 5 essais frais (le compteur vit sur le token, et la vérification lit
        // toujours le token le plus récent).
        LocalDateTime attemptsSince =
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(properties.getOtpValidMinutes());
        if (smsOtpRepository.sumAttemptsByPhoneSince(phoneNumber, attemptsSince) >= properties.getMaxAttempts()) {
            throw new YadonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "phone-otp-attempts-exceeded",
                    "Too Many Attempts", "Trop de tentatives échouées");
        }

        SmsOtpEntity token = smsOtpRepository
                .findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(phoneNumber)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.BAD_REQUEST, "phone-otp-invalid",
                        "Invalid OTP", "Code invalide ou expiré"));

        if (token.getAttempts() >= properties.getMaxAttempts()) {
            throw new YadonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "phone-otp-attempts-exceeded",
                    "Too Many Attempts", "Trop de tentatives échouées");
        }

        // BCrypt appelé avant le check expiration pour éviter les timing attacks
        boolean validCode = passwordEncoder.matches(code, token.getCodeHash());

        if (LocalDateTime.now(ZoneOffset.UTC).isAfter(token.getExpiresAt())) {
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "phone-otp-expired",
                    "OTP Expired", "Code expiré");
        }

        if (!validCode) {
            token.setAttempts(token.getAttempts() + 1);
            smsOtpRepository.save(token);
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "phone-otp-invalid",
                    "Invalid OTP", "Code invalide");
        }

        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        smsOtpRepository.save(token);
    }

    /** Les logs partent vers Loki : jamais de numéro complet en clair. */
    private static String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "***";
        }
        return phoneNumber.substring(0, phoneNumber.length() - 4) + "****";
    }
}

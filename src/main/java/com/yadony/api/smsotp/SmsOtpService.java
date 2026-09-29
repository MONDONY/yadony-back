package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.notifications.InvalidSmsRecipientException;
import com.yadony.api.notifications.SmsService;
import com.yadony.api.notifications.TwilioVerifyService;
import com.yadony.api.notifications.TwilioVerifyService.VerifyUnavailableException;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
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
    private final MessagesResolver messagesResolver;
    private final TwilioVerifyService twilioVerify;
    private final boolean devProfile;

    public SmsOtpService(SmsOtpRepository smsOtpRepository,
                          PasswordEncoder passwordEncoder,
                          SmsService smsService,
                          SmsOtpProperties properties,
                          @Autowired(required = false) FirebaseAuth firebaseAuth,
                          UserRepository userRepository,
                          FirebaseContactService firebaseContact,
                          AuditService auditService,
                          Environment environment,
                          MessagesResolver messagesResolver,
                          TwilioVerifyService twilioVerify) {
        this.smsOtpRepository = smsOtpRepository;
        this.passwordEncoder  = passwordEncoder;
        this.smsService       = smsService;
        this.properties       = properties;
        this.firebaseAuth     = firebaseAuth;
        this.userRepository   = userRepository;
        this.firebaseContact  = firebaseContact;
        this.auditService     = auditService;
        this.messagesResolver = messagesResolver;
        this.twilioVerify     = twilioVerify;
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        // Liste blanche, pas « tout sauf prod » : staging a de vrais testeurs et ses logs
        // partent vers Loki et Sentry. L'ancienne condition y relayait le code OTP en
        // clair dès que les SMS étaient coupés depuis le back-office.
        this.devProfile = activeProfiles.contains("dev") || activeProfiles.contains("test");
        // Une empreinte mal copiée ne casse rien (le code reste saisissable), mais la
        // lecture automatique sur Android ne marcherait pas sans que rien ne le dise.
        List<String> rejected = properties.getRejectedAndroidAppHashes();
        if (!rejected.isEmpty()) {
            log.warn("ANDROID_SMS_APP_HASHES : {} valeur(s) ignorée(s), une empreinte fait "
                    + "11 caractères base64", rejected.size());
        }
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

        // Par Verify, Twilio génère le code et yadony ne le voit jamais : la ligne ne porte
        // qu'un hash aléatoire, elle sert au budget d'envois et de tentatives, pas au contrôle.
        boolean viaVerify = usesVerify(phoneNumber);
        String code = viaVerify
                ? Long.toHexString(SECURE_RANDOM.nextLong()) + Long.toHexString(SECURE_RANDOM.nextLong())
                : String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(properties.getOtpValidMinutes());

        SmsOtpEntity entity = new SmsOtpEntity();
        entity.setPhoneNumber(phoneNumber);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(expiresAt);
        smsOtpRepository.save(entity);

        try {
            List<String> appHashes = properties.getAndroidAppHashes();
            if (viaVerify) {
                // Verify n'accepte qu'une empreinte : la première, celle du build Play.
                twilioVerify.start(phoneNumber, LocaleContextHolder.getLocale().getLanguage(),
                        appHashes.isEmpty() ? null : appHashes.get(0));
            } else {
                smsService.send(phoneNumber, withAppHashes(
                        messagesResolver.forRequest().get("sms.otp", code), appHashes));
            }
        } catch (VerifyUnavailableException e) {
            throw verifyUnavailable();
        } catch (InvalidSmsRecipientException e) {
            // Le transporteur refuse le numéro lui-même : 422 au client plutôt qu'un
            // « code envoyé » qui n'arrive jamais. L'exception fait rollback de la ligne OTP
            // (elle ne doit pas compter dans la fenêtre anti-spam).
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid-phone-number",
                    "Invalid Phone Number",
                    "Ce numéro n'est pas joignable par SMS, vérifie l'indicatif et le nombre de chiffres");
        }
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
        boolean validCode;
        try {
            validCode = usesVerify(phoneNumber)
                    ? twilioVerify.check(phoneNumber, code)
                    : passwordEncoder.matches(code, token.getCodeHash());
        } catch (VerifyUnavailableException e) {
            throw verifyUnavailable();
        }

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

    /**
     * Ajoute les empreintes SMS Retriever en fin de message, sur une ligne à part : Android
     * remet le SMS à l'app dont l'empreinte y figure, qui remplit le code sans que
     * l'utilisateur ouvre Messages. Le texte lu par l'utilisateur ne change pas.
     */
    static String withAppHashes(String text, List<String> appHashes) {
        return appHashes.isEmpty() ? text : text + "\n" + String.join(" ", appHashes);
    }

    /**
     * Vrai si ce numéro passe par Twilio Verify (États-Unis, Canada : voir
     * {@link TwilioVerifyService}). Jamais quand les SMS sont coupés : en dev, le code doit
     * rester local pour être relayé dans les logs. Lu à l'envoi comme au contrôle ; un
     * changement de configuration entre les deux invalide seulement le code en cours.
     */
    private boolean usesVerify(String phoneNumber) {
        return smsService.isEnabled() && twilioVerify.handles(phoneNumber);
    }

    private static YadonyBusinessException verifyUnavailable() {
        return new YadonyBusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-unavailable",
                "SMS OTP Unavailable",
                "L'envoi du code est momentanément indisponible, réessaie dans quelques minutes");
    }

    /**
     * Les logs partent vers Loki : jamais de numéro complet en clair. Même politique que
     * {@link Msisdn#mask} (indicatif + deux derniers chiffres) ; l'ancien masque ne cachait
     * que les quatre derniers chiffres, soit 10 000 valeurs possibles à côté du code OTP.
     */
    private static String maskPhone(String phoneNumber) {
        try {
            return Msisdn.mask(phoneNumber);
        } catch (IllegalArgumentException e) {
            return "***";
        }
    }
}

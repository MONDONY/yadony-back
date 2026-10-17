package com.yadony.api.emailotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EmailOtpProperties properties;
    private final EmailOtpRepository emailOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final ResendEmailService resendEmailService;
    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final AuditService auditService;
    private final MessagesResolver messagesResolver;
    private volatile String dummyHash;

    public EmailOtpService(EmailOtpProperties properties,
                           EmailOtpRepository emailOtpRepository,
                           PasswordEncoder passwordEncoder,
                           ResendEmailService resendEmailService,
                           @Autowired(required = false) FirebaseAuth firebaseAuth,
                           UserRepository userRepository,
                           FirebaseContactService firebaseContact,
                           AuditService auditService,
                           MessagesResolver messagesResolver) {
        this.properties         = properties;
        this.emailOtpRepository = emailOtpRepository;
        this.passwordEncoder    = passwordEncoder;
        this.resendEmailService = resendEmailService;
        this.firebaseAuth       = firebaseAuth;
        this.userRepository     = userRepository;
        this.firebaseContact    = firebaseContact;
        this.auditService       = auditService;
        this.messagesResolver   = messagesResolver;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Instant sendOtp(String rawEmail) {
        String email = normalize(rawEmail);
        int windowMinutes = properties.getRateWindowMinutes();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(windowMinutes);
        if (emailOtpRepository.countByEmailSince(email, since) >= properties.getMaxSendsPerWindow()) {
            throw new YadonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "rate-limit",
                    "Too Many Requests",
                    "Trop de codes demandés, réessaie dans " + windowMinutes + " min");
        }
        LocalDateTime hourAgo = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
        if (emailOtpRepository.countAllSince(hourAgo) >= properties.getMaxSendsPerHourGlobal()) {
            // ERROR → Sentry : soit un pic d'inscriptions à absorber (relever le budget),
            // soit un arrosage d'adresses qui passe sous les limites par adresse et par IP.
            log.error("Budget global d'envoi de codes email atteint ({} / h) : envois suspendus",
                    properties.getMaxSendsPerHourGlobal());
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "email-otp-budget-exceeded",
                    "Service Unavailable",
                    "L'envoi de codes est momentanément suspendu, réessaie dans quelques minutes");
        }

        // Seul le dernier code envoyé reste valable.
        emailOtpRepository.invalidateActive(email, LocalDateTime.now(ZoneOffset.UTC));

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(properties.getOtpValidMinutes());

        EmailOtpEntity entity = new EmailOtpEntity();
        entity.setEmail(email);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(expiresAt);
        emailOtpRepository.save(entity);

        resendEmailService.sendOtp(email, code, messagesResolver.forRequest());

        return expiresAt.toInstant(ZoneOffset.UTC);
    }

    public String verifyOtp(String rawEmail, String code) {
        String email = normalize(rawEmail);
        log.info("verifyOtp: email='{}'", maskEmail(email));
        consumeOtp(email, code);

        if (firebaseAuth == null) {
            log.warn("FirebaseAuth not available — returning null custom token (test mode)");
            return null;
        }
        String uid = resolveOrCreateFirebaseUid(email);
        try {
            // Le claim dit à AuthService que l'UID a déjà été résolu par ce service et
            // que l'adresse est portée par le UserRecord : il n'a donc pas à comparer
            // l'UID à l'email du body, ni à réécrire l'adresse.
            return firebaseAuth.createCustomToken(uid, java.util.Map.of("otp_channel", "email"));
        } catch (FirebaseAuthException e) {
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                    "Firebase Error", "Erreur lors de la création du token");
        }
    }

    /**
     * Rend l'UID Firebase qui détient déjà cette adresse, et n'en crée un qu'à défaut.
     *
     * <p>Le repli précédent prenait l'adresse elle-même comme UID dès qu'aucun compte
     * local n'y correspondait. Quand Firebase connaissait déjà l'adresse — inscription
     * par Google, ou base locale réinitialisée — cela fabriquait une <b>seconde</b>
     * identité pour la même personne, puis tentait de lui attribuer une adresse déjà
     * prise : Firebase répondait {@code EMAIL_ALREADY_EXISTS}. L'inscription échouait
     * après que le code OTP a été consommé, si bien que l'utilisateur ne voyait plus
     * que « Code invalide » sans jamais pouvoir aboutir.
     *
     * <p>Un compte Firebase portant cette adresse <b>est</b> le compte de cette
     * personne : la possession de l'adresse vient d'être prouvée par le code OTP. On
     * s'y connecte donc directement, quel que soit l'état de la base locale.
     */
    private String resolveOrCreateFirebaseUid(String email) {
        try {
            return firebaseAuth.getUserByEmail(email).getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
                throw new YadonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                        "Firebase Error", "Erreur lors de la résolution du compte");
            }
        }
        try {
            return firebaseAuth.createUser(
                    new UserRecord.CreateRequest().setEmail(email)).getUid();
        } catch (FirebaseAuthException e) {
            // Course possible : deux vérifications concurrentes pour une adresse
            // inconnue (double envoi, deux appareils). Le second createUser échoue,
            // on relit alors l'UID gagnant plutôt que d'échouer la requête.
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                try {
                    return firebaseAuth.getUserByEmail(email).getUid();
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
     * Rattache une adresse au compte authentifié, après avoir consommé le code OTP
     * dans la même opération : la preuve de possession est donc intrinsèque, un
     * appelant ne peut pas s'attribuer l'adresse d'un tiers.
     *
     * <p>Ajout seulement, jamais remplacement : l'email identifie le compte Firebase.
     * Un compte qui en porte déjà un est refusé (409).
     */
    public String attachEmailToAccount(String firebaseUid, String rawEmail, String code) {
        String email = normalize(rawEmail);
        log.info("attachEmailToAccount: uid={} email='{}'", firebaseUid, maskEmail(email));

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        // Preuve de possession avant toute écriture : le code est consommé ici.
        consumeOtp(email, code);

        if (firebaseContact.getContact(firebaseUid).email() != null) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "email-already-set",
                    "Email Already Set",
                    "Une adresse est déjà associée à ce compte et ne peut pas être remplacée");
        }

        if (firebaseContact.isEmailTakenByAnother(email, firebaseUid)) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "email-already-exists",
                    "Email Already Registered", "Cet email est déjà associé à un compte");
        }

        firebaseContact.updateEmail(firebaseUid, email);

        // Payload sans PII : l'adresse elle-même ne doit pas atterrir dans audit_log.
        auditService.log("USER", user.getId(), "USER_EMAIL_ATTACHED", user.getId(),
                java.util.Map.of("verifiedBy", "email-otp"));

        // Firebase vient de révoquer le jeton de rafraîchissement du client (changement
        // de coordonnées) : on lui rend de quoi rouvrir sa session sans la perdre.
        return firebaseContact.issueSessionToken(firebaseUid);
    }

    /**
     * Valide le code reçu pour cette adresse et le consomme (usage unique).
     * Lève si le code est absent, expiré, faux, ou si le budget de tentatives est épuisé.
     *
     * <p>Extrait de {@link #verifyOtp} pour que le rattachement d'une adresse à un compte
     * puisse exiger la même preuve de possession, dans la même requête que l'écriture.
     */
    void consumeOtp(String email, String code) {

        // Budget global par adresse : sans lui, chaque renvoi de code offrirait
        // 5 essais frais (le compteur vit sur le token, et la vérification lit
        // toujours le token le plus récent).
        LocalDateTime attemptsSince =
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(properties.getOtpValidMinutes());
        if (emailOtpRepository.sumAttemptsByEmailSince(email, attemptsSince) >= properties.getMaxAttempts()) {
            throw new YadonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "otp-attempts-exceeded",
                    "Too Many Attempts", "Trop de tentatives échouées");
        }

        EmailOtpEntity token = emailOtpRepository
                .findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(email)
                .orElse(null);
        if (token == null) {
            // Même coût qu'un vrai contrôle : une réponse plus rapide révélerait qu'aucun
            // code n'est en attente pour cette adresse.
            passwordEncoder.matches(code, dummyHash());
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "otp-invalid",
                    "Invalid OTP", "Code invalide ou expiré");
        }

        if (token.getAttempts() >= properties.getMaxAttempts()) {
            throw new YadonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "otp-attempts-exceeded",
                    "Too Many Attempts", "Trop de tentatives échouées");
        }

        // BCrypt appelé avant le check expiration pour éviter les timing attacks
        boolean validCode = passwordEncoder.matches(code, token.getCodeHash());

        if (LocalDateTime.now(ZoneOffset.UTC).isAfter(token.getExpiresAt())) {
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "otp-expired",
                    "OTP Expired", "Code expiré");
        }

        if (!validCode) {
            // Transaction séparée : l'exception ci-dessous annule celle de l'appelant.
            emailOtpRepository.incrementAttempts(token.getId());
            throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "otp-invalid",
                    "Invalid OTP", "Code invalide");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        token.setUsedAt(now);
        emailOtpRepository.save(token);
        // Les codes plus anciens encore valides ne doivent pas survivre à une connexion.
        emailOtpRepository.invalidateActive(email, now);
    }

    /**
     * Forme canonique d'une adresse : sans espaces autour, en minuscules. Firebase ignore
     * la casse ; sans cela, « A@x.com » et « a@x.com » avaient chacune leur budget
     * d'envois et d'essais pour une même boîte.
     */
    static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Hash BCrypt jetable, calculé une fois, pour égaliser le temps de réponse. */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode("dummy-otp-" + SECURE_RANDOM.nextInt());
            dummyHash = hash;
        }
        return hash;
    }

    /** Les logs partent vers Loki : jamais d'adresse complète en clair. */
    private static String maskEmail(String email) {
        if (email == null) {
            return "null";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}

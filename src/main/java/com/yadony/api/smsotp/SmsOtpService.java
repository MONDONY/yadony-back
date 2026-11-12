package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.PhoneOwnershipService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.notifications.InvalidSmsRecipientException;
import com.yadony.api.notifications.SmsDeliveryException;
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
import java.util.Optional;

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
    private final PhoneOwnershipService phoneOwnership;
    private final boolean devProfile;
    private volatile String dummyHash;

    /**
     * Publie {@link com.yadony.api.auth.events.UserPhoneAttachedEvent} ; injecté par setter pour
     * ne pas toucher aux constructeurs des tests unitaires (sans lui, rien n'est publié).
     */
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    void setEventPublisher(org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Réglage du back-office {@code sms_otp_registered_numbers_only}, injecté par setter pour la
     * même raison que l'éditeur d'événements. Absent (tests unitaires) : la propriété
     * {@code yadony.sms.registered-numbers-only} prend le relais.
     */
    private com.yadony.api.config.PlatformSettingsService platformSettings;

    @Autowired(required = false)
    void setPlatformSettings(com.yadony.api.config.PlatformSettingsService platformSettings) {
        this.platformSettings = platformSettings;
    }

    /** Lu à chaque envoi : une bascule depuis le back-office s'applique sans redémarrage. */
    private boolean registeredNumbersOnly() {
        return platformSettings != null
                ? platformSettings.smsOtpRegisteredNumbersOnly()
                : properties.isRegisteredNumbersOnly();
    }

    /**
     * Plafond global des SMS de code sur 1 h, réglé dans le back-office
     * ({@code sms_otp_max_sends_per_hour_global}) ; la propriété prend le relais sans lui.
     */
    private int maxSendsPerHourGlobal() {
        return platformSettings != null
                ? platformSettings.smsOtpMaxSendsPerHourGlobal()
                : properties.getMaxSendsPerHourGlobal();
    }

    /** Même plafond sur 24 h ({@code sms_otp_max_sends_per_day_global}). */
    private int maxSendsPerDayGlobal() {
        return platformSettings != null
                ? platformSettings.smsOtpMaxSendsPerDayGlobal()
                : properties.getMaxSendsPerDayGlobal();
    }

    /**
     * Indicatif bloqué ({@code sms_blocked_country_codes} du back-office) par lequel commence ce
     * numéro, lu à chaque envoi comme {@link #registeredNumbersOnly()}. Sans le réglage (tests
     * unitaires) : aucun pays bloqué.
     */
    private Optional<String> blockedCallingCode(String phoneNumber) {
        return platformSettings != null
                ? platformSettings.smsBlockedCallingCode(phoneNumber)
                : Optional.empty();
    }

    /**
     * 503 {@code sms-otp-disabled}, le code que les versions installées de l'app savent déjà
     * afficher, précisé par {@code reason: country-blocked} et {@code callingCode}. Le texte nomme
     * le pays dans la langue de la requête : sans compte, il renvoie vers l'email, Google ou
     * Apple ; un compte connecté (ajout d'un numéro) est déjà connecté autrement.
     */
    private YadonyBusinessException countryBlocked(String callingCode, String signedInUid) {
        Messages m = messagesResolver.forRequest();
        boolean guest = signedInUid == null;
        String form = guest ? "in" : "of";
        String country = m.find("sms.country." + form + "." + callingCode)
                .orElseGet(() -> m.get("sms.country." + form + ".unknown"));
        String detail = guest
                ? m.get("sms.otp.country-blocked.guest", country)
                : m.get("sms.otp.country-blocked.signed-in", country, callingCode);
        return new YadonyBusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-disabled", "SMS OTP Unavailable", detail,
                Map.of("reason", "country-blocked", "callingCode", callingCode));
    }

    /** Seuil de suspension, lu à chaque envoi comme {@link #registeredNumbersOnly()}. */
    private int maxNumbersPerAccount() {
        return platformSettings != null
                ? platformSettings.smsOtpMaxNumbersPerAccount()
                : properties.getMaxNumbersPerAccount();
    }

    private final SmsOtpNumberTracker numberTracker = new SmsOtpNumberTracker(java.time.Clock.systemUTC());

    /**
     * Compte connecté qui vise trop de numéros différents en 24 h : refus (403, même code que
     * pour un compte suspendu) et {@link SmsOtpNumberHoppingEvent}, sur lequel le package auth
     * suspend le compte et alerte le back-office. Une fois suspendu, le compte reçoit 403 sur
     * toute requête (FirebaseTokenFilter) : il ne peut plus faire vérifier aucun numéro.
     */
    private void guardAgainstNumberHopping(String signedInUid, String phoneNumber) {
        int threshold = maxNumbersPerAccount();
        int distinctNumbers = numberTracker.record(signedInUid, phoneNumber);
        if (distinctNumbers < threshold) {
            return;
        }
        // ERROR → Sentry, en plus de l'alerte du back-office levée par l'écouteur.
        log.error("Compte {} : codes SMS demandés pour {} numéros différents en 24 h (seuil {}), "
                + "suspension automatique", signedInUid, distinctNumbers, threshold);
        // Remise à zéro : tant qu'il est suspendu, FirebaseTokenFilter refuse toutes ses requêtes ;
        // réactivé par un admin, il ne doit pas être resuspendu sur ses numéros d'avant.
        numberTracker.forget(signedInUid);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SmsOtpNumberHoppingEvent(signedInUid, distinctNumbers, threshold));
        }
        throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "account-suspended", "problem.account-suspended");
    }

    /** Numéros visés par chaque téléphone SANS compte connecté (écran de connexion), sur 24 h. */
    private final SmsOtpNumberTracker deviceTracker = new SmsOtpNumberTracker(java.time.Clock.systemUTC());

    /**
     * Téléphone sans compte qui vise trop de numéros différents en 24 h : alerte seulement, via
     * {@link SmsOtpDeviceNumberHoppingEvent} (le package admin lève l'alerte du back-office). Rien
     * n'est bloqué ici : il n'y a pas de compte à suspendre, et un vrai utilisateur qui se
     * trompe de numéro ne doit pas être puni ; en mode « numéros inscrits », les numéros sans
     * compte ne reçoivent de toute façon aucun SMS. L'appareil repart de zéro après l'alerte.
     */
    private void watchDevice(String deviceKey, String phoneNumber) {
        int threshold = maxNumbersPerAccount();
        int distinctNumbers = deviceTracker.record(deviceKey, phoneNumber);
        if (distinctNumbers < threshold) {
            return;
        }
        // WARN → Sentry, en plus de l'alerte du back-office levée par l'écouteur.
        log.warn("Téléphone sans compte {} : codes SMS demandés pour {} numéros différents en 24 h (seuil {})",
                deviceKey, distinctNumbers, threshold);
        deviceTracker.forget(deviceKey);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SmsOtpDeviceNumberHoppingEvent(deviceKey, distinctNumbers, threshold));
        }
    }

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
                          TwilioVerifyService twilioVerify,
                          PhoneOwnershipService phoneOwnership) {
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
        this.phoneOwnership   = phoneOwnership;
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

    /** Envoi sans compte connecté (ou en invité) : seul un numéro déjà inscrit reçoit un code. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Instant sendOtp(String phoneNumber) {
        return sendOtp(phoneNumber, null);
    }

    /**
     * @param signedInUid UID Firebase du compte connecté qui demande le code, {@code null}
     *                    sans compte ou en invité : il autorise un numéro encore inconnu,
     *                    à ajouter à ce compte (voir {@link #mayReceiveCode}).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Instant sendOtp(String phoneNumber, String signedInUid) {
        return sendOtp(phoneNumber, signedInUid, null);
    }

    /**
     * @param deviceKey identifiant du téléphone qui demande le code ({@code device:<id>} ou
     *                  {@code guest:<uid>}, voir {@code SmsOtpController#deviceKey}), {@code null}
     *                  s'il est inconnu : sans compte connecté, il sert à alerter le back-office
     *                  quand un même téléphone vise plusieurs numéros.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Instant sendOtp(String phoneNumber, String signedInUid, String deviceKey) {
        // En dev/test, SMS désactivé est le réglage normal (voir le relais de log
        // plus bas) : on ne bloque qu'en prod, où un flag resté à false alors que
        // l'écran est accessible (build client périgé, deep link) enverrait
        // sinon un "code envoyé" silencieux qui n'arrive jamais.
        // Un compte de revue n'envoie aucun SMS : il reste utilisable SMS coupés.
        Optional<String> reviewCode = properties.reviewCodeFor(phoneNumber);
        // Avant tout autre contrôle : une demande refusée ensuite compte quand même.
        if (signedInUid != null && reviewCode.isEmpty()) {
            guardAgainstNumberHopping(signedInUid, phoneNumber);
        } else if (deviceKey != null && reviewCode.isEmpty()) {
            watchDevice(deviceKey, phoneNumber);
        }
        if (reviewCode.isEmpty() && !smsService.isEnabled() && !devProfile) {
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-disabled",
                    "SMS OTP Disabled", messagesResolver.forRequest().get("sms.otp.disabled"));
        }

        if (!properties.isCountryAllowed(phoneNumber)) {
            // Hors des marchés servis : refus avant tout envoi facturé (SMS pumping).
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "phone-country-not-supported", "problem.phone-country-not-supported");
        }

        if (reviewCode.isEmpty()) {
            // Pays dont les SMS n'arrivent pas (Twilio les facture et les dit « Delivered ») :
            // refus avant toute écriture et tout envoi facturé.
            Optional<String> blocked = blockedCallingCode(phoneNumber);
            if (blocked.isPresent()) {
                log.info("Code SMS refusé : indicatif +{} bloqué ({})", blocked.get(), maskPhone(phoneNumber));
                throw countryBlocked(blocked.get(), signedInUid);
            }
        }

        if (reviewCode.isEmpty() && registeredNumbersOnly()
                && !mayReceiveCode(phoneNumber, signedInUid)) {
            // Avant toute écriture et tout envoi facturé. Même code que les SMS coupés : l'app
            // l'affiche déjà (« La connexion par téléphone n'est pas encore disponible »).
            log.info("Code SMS refusé : {} n'est porté par aucun compte", maskPhone(phoneNumber));
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-disabled",
                    "SMS OTP Unavailable",
                    messagesResolver.forRequest().get("sms.otp.registered-only"));
        }

        int windowMinutes = properties.getRateWindowMinutes();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(windowMinutes);
        if (smsOtpRepository.countByPhoneSince(phoneNumber, since) >= properties.getMaxSendsPerWindow()) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "phone-otp-rate-limit", "problem.rate-limit", windowMinutes);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Au-delà du quota du jour, un SMS par heure seulement (voir le canal email) : un
        // refus de 24 h laissait n'importe qui bloquer le propriétaire du numéro.
        if (smsOtpRepository.countByPhoneSince(phoneNumber, now.minusDays(1)) >= properties.getMaxSendsPerDay()
                && smsOtpRepository.countByPhoneSince(phoneNumber, now.minusHours(1)) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "phone-otp-daily-limit", "problem.otp-daily-limit", Map.of("retryAfterSeconds", 3600L));
        }
        // Inutile de facturer un SMS dont le code serait refusé à la vérification.
        ensureLoginBudget(phoneNumber, now);
        int perHourGlobal = maxSendsPerHourGlobal();
        int perDayGlobal = maxSendsPerDayGlobal();
        if (smsOtpRepository.countAllSince(now.minusHours(1)) >= perHourGlobal
                || smsOtpRepository.countAllSince(now.minusDays(1)) >= perDayGlobal) {
            // ERROR → Sentry : chaque SMS est facturé. Pic légitime (relever le budget dans le
            // back-office, Paramètres) ou pompage réparti sur des milliers de numéros et d'IP.
            log.error("Budget global de SMS de code atteint ({} / h, {} / 24 h) : envois suspendus",
                    perHourGlobal, perDayGlobal);
            throw YadonyBusinessException.localized(HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-budget-exceeded", "problem.sms-otp-budget-exceeded");
        }

        // Seul le dernier code envoyé reste valable.
        smsOtpRepository.invalidateActive(phoneNumber, now);

        // Par Verify, Twilio génère le code et yadony ne le voit jamais : la ligne ne porte
        // qu'un hash aléatoire, elle sert au budget d'envois et de tentatives, pas au contrôle.
        boolean viaVerify = usesVerify(phoneNumber);
        String code = reviewCode.orElseGet(() -> viaVerify
                ? Long.toHexString(SECURE_RANDOM.nextLong()) + Long.toHexString(SECURE_RANDOM.nextLong())
                : String.format("%06d", SECURE_RANDOM.nextInt(1_000_000)));
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(properties.getOtpValidMinutes());

        SmsOtpEntity entity = new SmsOtpEntity();
        entity.setPhoneNumber(phoneNumber);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(expiresAt);
        smsOtpRepository.save(entity);

        if (reviewCode.isPresent()) {
            // WARN → Sentry : une connexion sans SMS doit rester visible en prod.
            log.warn("Compte de revue {} : code fixe, aucun SMS envoyé", maskPhone(phoneNumber));
            return expiresAt.toInstant(ZoneOffset.UTC);
        }

        try {
            List<String> appHashes = properties.getAndroidAppHashes();
            if (viaVerify) {
                // Verify n'accepte qu'une empreinte : la première, celle du build Play.
                twilioVerify.start(phoneNumber, LocaleContextHolder.getLocale().getLanguage(),
                        appHashes.isEmpty() ? null : appHashes.get(0));
            } else {
                smsService.sendOrThrow(phoneNumber, withAppHashes(
                        messagesResolver.forRequest().get("sms.otp", code), appHashes));
            }
        } catch (VerifyUnavailableException e) {
            throw verifyUnavailable();
        } catch (SmsDeliveryException e) {
            // Le transporteur n'a rien envoyé (compte Twilio suspendu → 401, route refusée →
            // 21612, panne) : 503 et le repli email, Google ou Apple, au lieu d'un « code envoyé »
            // qui n'arrive jamais (Sentry YADONY-BACK-PROD-G). Même code que les SMS coupés, que
            // l'application sait déjà afficher. L'exception fait rollback de la ligne OTP.
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-disabled",
                    "SMS OTP Unavailable",
                    messagesResolver.forRequest().get("sms.otp.delivery-failed"));
        } catch (InvalidSmsRecipientException e) {
            // Le transporteur refuse le numéro lui-même : 422 au client plutôt qu'un
            // « code envoyé » qui n'arrive jamais. L'exception fait rollback de la ligne OTP
            // (elle ne doit pas compter dans la fenêtre anti-spam).
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-phone-number", "problem.invalid-phone-number");
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
            throw YadonyBusinessException.localized(HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error", "problem.firebase-error.token-creation");
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
                throw YadonyBusinessException.localized(HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error", "problem.firebase-error.account-resolution");
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
                    throw YadonyBusinessException.localized(HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error", "problem.firebase-error.account-resolution");
                }
            }
            throw YadonyBusinessException.localized(HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error", "problem.firebase-error.account-creation");
        }
    }

    /**
     * Rattache un numéro au compte authentifié, après avoir consommé le code OTP
     * dans la même opération : la preuve de possession est donc intrinsèque, un
     * appelant ne peut pas s'attribuer le numéro d'un tiers.
     *
     * <p>Ajout seulement, jamais remplacement : le numéro identifie le compte
     * Firebase. Un compte qui en porte déjà un est refusé (409).
     *
     * <p>Numéro porté par un autre compte Firebase : voir {@link PhoneOwnershipService}
     * (fantôme libéré, compte en suppression ou vrai compte refusés en 409).
     */
    public String attachPhoneToAccount(String firebaseUid, String phoneNumber, String code) {
        log.info("attachPhoneToAccount: uid={} phone='{}'", firebaseUid, maskPhone(phoneNumber));

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));

        // Preuve de possession avant toute écriture : le code est consommé ici.
        consumeOtp(phoneNumber, code);

        if (firebaseContact.getContact(firebaseUid).phoneNumber() != null) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "phone-already-set", "problem.phone-already-set");
        }

        phoneOwnership.claimPhone(user, phoneNumber);

        // Payload sans PII : le numéro lui-même ne doit pas atterrir dans audit_log.
        auditService.log("USER", user.getId(), "USER_PHONE_ATTACHED", user.getId(),
                Map.of("verifiedBy", "sms-otp"));
        // Colis envoyés à ce numéro avant qu'il soit rattaché : les réceptions les retrouvent
        // (ReceptionCatchUpListener, après validation et en asynchrone).
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.yadony.api.auth.events.UserPhoneAttachedEvent(user.getId()));
        }

        // Firebase vient de révoquer le jeton de rafraîchissement du client (changement
        // de coordonnées) : on lui rend de quoi rouvrir sa session sans la perdre.
        return firebaseContact.issueSessionToken(firebaseUid);
    }

    /**
     * Valide le code reçu pour ce numéro et le consomme (usage unique).
     * Lève si le code est absent, expiré, faux, ou si le budget de tentatives est épuisé.
     */
    void consumeOtp(String phoneNumber, String code) {

        // Avant tout contrôle du code : même un code juste est refusé au-delà du budget.
        ensureLoginBudget(phoneNumber, LocalDateTime.now(ZoneOffset.UTC));

        // Budget global par numéro : sans lui, chaque renvoi de code offrirait
        // 5 essais frais (le compteur vit sur le token, et la vérification lit
        // toujours le token le plus récent).
        LocalDateTime attemptsSince =
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(properties.getOtpValidMinutes());
        if (smsOtpRepository.sumAttemptsByPhoneSince(phoneNumber, attemptsSince) >= properties.getMaxAttempts()) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "phone-otp-attempts-exceeded", "problem.otp-attempts-exceeded");
        }

        SmsOtpEntity token = smsOtpRepository
                .findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(phoneNumber)
                .orElse(null);
        if (token == null) {
            // Même coût qu'un vrai contrôle : une réponse plus rapide révélerait qu'aucun
            // code n'est en attente pour ce numéro.
            passwordEncoder.matches(code, dummyHash());
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "phone-otp-invalid", "problem.otp-invalid");
        }

        if (token.getAttempts() >= properties.getMaxAttempts()) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "phone-otp-attempts-exceeded", "problem.otp-attempts-exceeded");
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
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "phone-otp-expired", "problem.otp-expired");
        }

        if (!validCode) {
            // Transaction séparée : l'exception ci-dessous annule celle de l'appelant.
            smsOtpRepository.incrementAttempts(token.getId());
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "phone-otp-invalid", "problem.otp-invalid.short");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        token.setUsedAt(now);
        smsOtpRepository.save(token);
        // Les codes plus anciens encore valides ne doivent pas survivre à une connexion.
        smsOtpRepository.invalidateActive(phoneNumber, now);
    }

    /**
     * Vrai si ce numéro peut recevoir un code quand {@code registered-numbers-only} est actif :
     * <ul>
     *   <li>il est déjà porté par un compte Firebase (connexion d'un inscrit) ;</li>
     *   <li>ou le demandeur est un compte connecté, ni invité ni sans fiche Yadony, qui n'a
     *       pas encore de numéro : il ajoute le sien (voir {@link #attachPhoneToAccount}).</li>
     * </ul>
     * Une panne Firebase vaut « numéro inconnu » : on refuse plutôt que de payer un SMS vers
     * un inconnu, la connexion par email, Google ou Apple reste ouverte.
     */
    private boolean mayReceiveCode(String phoneNumber, String signedInUid) {
        if (firebaseContact.findUidByPhone(phoneNumber).isPresent()) {
            return true;
        }
        return signedInUid != null
                && userRepository.findByFirebaseUid(signedInUid).isPresent()
                && firebaseContact.getContact(signedInUid).phoneNumber() == null;
    }

    /**
     * Refuse (429 {@code phone-otp-login-limit}) quand ce numéro a déjà réussi
     * {@code maxLoginsPerWindow} connexions sur la fenêtre, même si le code est bon.
     */
    private void ensureLoginBudget(String phoneNumber, LocalDateTime now) {
        int windowMinutes = properties.getLoginWindowMinutes();
        if (smsOtpRepository.countSuccessfulSince(phoneNumber, now.minusMinutes(windowMinutes))
                >= properties.getMaxLoginsPerWindow()) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "phone-otp-login-limit", "problem.phone-otp-login-limit", Map.of("retryAfterSeconds", windowMinutes * 60L), windowMinutes);
        }
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
        // Le code d'un compte de revue est contrôlé ici, Twilio ne l'a jamais émis.
        return properties.reviewCodeFor(phoneNumber).isEmpty()
                && smsService.isEnabled() && twilioVerify.handles(phoneNumber);
    }

    private static YadonyBusinessException verifyUnavailable() {
        return YadonyBusinessException.localized(HttpStatus.SERVICE_UNAVAILABLE, "sms-otp-unavailable", "problem.sms-otp-unavailable");
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

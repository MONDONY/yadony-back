package com.yadony.api.smsotp;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserPhoneAttachedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Numéro déclaré sans SMS, pour les comptes dont l'indicatif est bloqué (SMS jamais reçus).
 *
 * <p>Le numéro vit dans le claim Firebase {@code declared_phone} ; en base, seule son empreinte
 * ({@code users.declared_phone_hash}). Il n'est JAMAIS écrit dans {@code phoneNumber} de Firebase :
 * le reste du système y lit « vérifié par SMS » (connexion, paiements, rattachement des colis).
 * Aucune unicité : deux comptes peuvent déclarer le même numéro.
 */
@Service
public class DeclaredPhoneService {

    private static final Logger log = LoggerFactory.getLogger(DeclaredPhoneService.class);

    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final AuditService auditService;
    private final SmsOtpService smsOtpService;
    private final SmsOtpProperties properties;
    private final DeclaredPhoneHasher hasher;

    public DeclaredPhoneService(UserRepository userRepository, FirebaseContactService firebaseContact,
                                AuditService auditService, SmsOtpService smsOtpService,
                                SmsOtpProperties properties, DeclaredPhoneHasher hasher) {
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.auditService = auditService;
        this.smsOtpService = smsOtpService;
        this.properties = properties;
        this.hasher = hasher;
    }

    /**
     * Déclare (ou remplace) le numéro du compte. Refusé (409) hors des indicatifs bloqués : le
     * compte doit alors passer par le SMS, sans quoi cette route contournerait la vérification.
     * Un autre numéro remet le niveau à {@code DECLARED} et efface la validation d'un administrateur ;
     * le même numéro la conserve. La garde « numéro SMS déjà là » lit Firebase sans cache et échoue
     * fermée (503 si la lecture rate). Le même numéro déjà en place (empreinte, niveau ET claim) ne
     * fait rien : ni audit (ligne immuable) ni écriture Firebase ; la limite de débit par compte
     * ({@code SMS_OTP_DECLARE_ACCOUNT}) borne le reste.
     */
    @Transactional
    public void declare(String firebaseUid, String e164) {
        if (!hasher.isConfigured() || !firebaseContact.isAvailable()) {
            throw unavailable();
        }
        if (!properties.isCountryAllowed(e164)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                    "phone-country-not-supported", "problem.phone-country-not-supported");
        }
        if (smsOtpService.blockedCallingCode(e164).isEmpty()) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                    "phone-declare-not-allowed", "problem.phone-declare-not-allowed");
        }
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid).orElseThrow(() ->
                YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        // Lecture stricte : le cache de 5 min peut ignorer un numéro rattaché par SMS à l'instant, et une
        // panne Firebase ne doit pas passer pour « aucun numéro vérifié ».
        FirebaseContactService.Contact contact = firebaseContact.readContactStrict(firebaseUid)
                .orElseThrow(DeclaredPhoneService::unavailable);
        if (contact.phoneNumber() != null) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                    "phone-already-set", "problem.phone-already-set");
        }

        String hash = hasher.hash(e164);
        boolean sameNumber = hash.equals(user.getDeclaredPhoneHash())
                && user.getDeclaredPhoneLevel() != null;
        if (sameNumber && e164.equals(contact.declaredPhone())) {
            // Rien ne change : pas de ligne d'audit immuable ni d'écriture Firebase à chaque appel.
            return;
        }
        if (!sameNumber) {
            user.setDeclaredPhoneHash(hash);
            user.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
            user.setDeclaredPhoneVerifiedAt(null);
            user.setDeclaredPhoneVerifiedBy(null);
        }
        // Flush d'abord : vérifie la version (@Version) et pose le verrou de ligne, de sorte qu'un
        // échec de la base survienne AVANT l'écriture Firebase. Puis l'audit, puis Firebase en
        // dernier : s'il refuse, la transaction est annulée (base et audit) sans claim orphelin.
        userRepository.saveAndFlush(user);
        log.info("Numéro déclaré sans SMS : uid={} ({})", firebaseUid, Msisdn.mask(e164));
        // Payload sans PII : ni numéro ni empreinte dans audit_log.
        auditService.log("USER", user.getId(), "USER_PHONE_DECLARED", user.getId(),
                Map.of("level", user.getDeclaredPhoneLevel().name()));
        firebaseContact.setDeclaredPhone(firebaseUid, e164);
    }

    private static YadonyBusinessException unavailable() {
        return YadonyBusinessException.localized(HttpStatus.SERVICE_UNAVAILABLE,
                "declared-phone-unavailable", "problem.declared-phone-unavailable");
    }

    /**
     * Un numéro vérifié par SMS vient d'être rattaché : il fait foi, la déclaration (claim, empreinte,
     * niveau, validation) tombe. Le claim (numéro en clair) part en premier : si Firebase refuse,
     * l'exception annule la transaction, l'empreinte reste et un nouvel essai demeure possible. Rien
     * n'est avalé (Spring journalise en ERROR les échecs d'un écouteur après validation) ; le
     * rattachement, lui, est déjà acquis. Se déclenche aussi si seul un claim orphelin subsiste.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPhoneAttached(UserPhoneAttachedEvent event) {
        userRepository.findById(event.userId()).ifPresent(user -> {
            if (user.getDeclaredPhoneHash() == null
                    && firebaseContact.getContact(user.getFirebaseUid()).declaredPhone() == null) {
                return;
            }
            firebaseContact.setDeclaredPhone(user.getFirebaseUid(), null);
            user.setDeclaredPhoneHash(null);
            user.setDeclaredPhoneLevel(null);
            user.setDeclaredPhoneVerifiedAt(null);
            user.setDeclaredPhoneVerifiedBy(null);
            userRepository.save(user);
        });
    }
}

package com.yadony.api.auth;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.smsotp.SmsOtpNumberHoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Suspend le compte qui demande des codes SMS pour trop de numéros différents, et alerte le
 * back-office (Alertes, cloche, Sentry, Telegram) : demande du propriétaire après le pompage de
 * SMS du 08/11/2026.
 *
 * <p>Écouteur SYNCHRONE : l'événement est publié dans la transaction d'envoi du code, que
 * {@code SmsOtpService} annule aussitôt en refusant la demande. La suspension s'écrit donc dans
 * sa propre transaction ({@code REQUIRES_NEW}), et l'alerte dans celle de
 * {@link AdminAlertEscalator}. Rien ici ne doit lever : le demandeur doit recevoir son 403,
 * jamais une erreur 500.
 */
@Component
class SmsOtpNumberHoppingListener {

    static final String ALERT_PREFIX = "SMS_OTP_NUMBERS_";
    static final String REASON_CODE = "sms_otp_multiple_numbers";

    private static final Logger log = LoggerFactory.getLogger(SmsOtpNumberHoppingListener.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final AdminAlertEscalator alerts;
    private final TransactionTemplate independentTransaction;

    SmsOtpNumberHoppingListener(UserRepository userRepository, UserService userService,
                                AdminAlertEscalator alerts, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.alerts = alerts;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Ce que la transaction de suspension a trouvé et fait. */
    private record Outcome(Optional<UUID> userId, boolean suspended) {
        static final Outcome NONE = new Outcome(Optional.empty(), false);
    }

    @EventListener
    public void onNumberHopping(SmsOtpNumberHoppingEvent event) {
        Outcome outcome = Outcome.NONE;
        try {
            // Lecture ET écriture hors de la transaction SERIALIZABLE de l'envoi, qui va être annulée.
            outcome = independentTransaction.execute(status -> {
                Optional<UUID> userId = userRepository.findByFirebaseUid(event.firebaseUid())
                        .map(UserEntity::getId);
                boolean suspended = userId.map(id -> userService.suspendAutomatically(id, REASON_CODE,
                        "Suspension automatique : codes SMS demandés pour "
                                + event.distinctNumbers() + " numéros différents en 24 h",
                        Map.of("distinctNumbers", event.distinctNumbers(),
                                "threshold", event.threshold())))
                        .orElse(false);
                return new Outcome(userId, suspended);
            });
        } catch (RuntimeException e) {
            log.error("Suspension automatique impossible pour le compte {} (codes SMS, {} numéros)",
                    event.firebaseUid(), event.distinctNumbers(), e);
        }
        if (outcome == null) {
            outcome = Outcome.NONE;
        }
        raiseAlert(event, outcome.userId(), outcome.suspended());
    }

    private void raiseAlert(SmsOtpNumberHoppingEvent event, Optional<UUID> userId, boolean suspended) {
        String subject = userId.map(UUID::toString).orElseGet(() -> truncate(event.firebaseUid()));
        String detail = userId.isEmpty()
                ? "Compte Firebase " + event.firebaseUid() + " sans fiche Yadony : codes SMS demandés pour "
                        + event.distinctNumbers() + " numéros différents en 24 h. Demandes refusées, aucune "
                        + "suspension possible sans fiche."
                : (suspended ? "Compte " + userId.get() + " suspendu automatiquement : codes SMS demandés pour "
                        : "Compte " + userId.get() + " (déjà suspendu ou inactif) : codes SMS demandés pour ")
                        + event.distinctNumbers() + " numéros différents en 24 h (seuil " + event.threshold()
                        + "). Possible pompage de SMS. Si c'est une erreur, lever la suspension depuis la "
                        + "fiche de l'utilisateur.";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("firebaseUid", event.firebaseUid());
        userId.ifPresent(id -> context.put("userId", id.toString()));
        context.put("distinctNumbers", event.distinctNumbers());
        context.put("threshold", event.threshold());
        context.put("suspended", suspended);
        try {
            alerts.raiseOnce(ALERT_PREFIX + subject, "CRITICAL", detail, context);
        } catch (RuntimeException e) {
            log.error("Alerte back-office impossible pour le compte {} (codes SMS, {} numéros)",
                    event.firebaseUid(), event.distinctNumbers(), e);
        }
    }

    /** {@code admin_alerts.type} fait 60 caractères : un UID Firebase peut en compter 128. */
    private static String truncate(String uid) {
        int room = AdminAlertEscalator.TYPE_MAX_LENGTH - ALERT_PREFIX.length();
        return uid.length() <= room ? uid : uid.substring(0, room);
    }
}

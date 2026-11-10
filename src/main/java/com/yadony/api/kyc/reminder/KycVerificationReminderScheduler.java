package com.yadony.api.kyc.reminder;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.UserZones;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Relance J+1 / J+3 / J+7 des comptes qui n'ont pas fini leur vérification d'identité (3 envois max,
 * dans la fenêtre horaire locale).
 *
 * <p>Chaque relance est réservée dans sa propre transaction (incrément du compteur + horodatage,
 * commit) AVANT l'envoi, qui part hors transaction : un échec sur un compte n'annule ni les
 * compteurs des autres ni ne rejoue des push déjà parties.
 */
@Component
public class KycVerificationReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(KycVerificationReminderScheduler.class);
    static final String NOTIFICATION_TYPE = "KYC_ACTION_REQUIRED";

    private final KycReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final AuditService auditService;
    private final TransactionOperations transactions;
    private final KycReminderProperties properties;
    private final Clock clock;

    @Autowired
    public KycVerificationReminderScheduler(KycReminderRepository reminderRepository, UserRepository userRepository,
                                            NotificationDispatcher notificationDispatcher, AuditService auditService,
                                            TransactionOperations transactions, KycReminderProperties properties) {
        this(reminderRepository, userRepository, notificationDispatcher, auditService, transactions, properties,
                Clock.systemUTC());
    }

    KycVerificationReminderScheduler(KycReminderRepository reminderRepository, UserRepository userRepository,
                                     NotificationDispatcher notificationDispatcher, AuditService auditService,
                                     TransactionOperations transactions, KycReminderProperties properties,
                                     Clock clock) {
        this.reminderRepository = reminderRepository;
        this.userRepository = userRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.auditService = auditService;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${yadony.kyc.verification-reminders.cron:0 40 * * * *}")
    public void remind() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        // La configuration peut être en minuscules ; le SQL compare en majuscules.
        List<String> retryableCodes = properties.retryableRejectionCodes() == null ? List.of()
                : properties.retryableRejectionCodes().stream()
                        .map(code -> code.trim().toUpperCase(Locale.ROOT)).toList();
        Duration first = properties.firstDelay();
        Duration second = properties.secondDelay();
        Duration third = properties.thirdDelay();
        List<UUID> candidates = reminderRepository.findCandidates(
                now.minus(first), now.minus(second), now.minus(third),
                now.minus(second.minus(first)), now.minus(third.minus(second)), retryableCodes);
        int sent = 0;
        for (UUID id : candidates) {
            Reservation reservation;
            try {
                reservation = transactions.execute(status -> reserve(id, now, retryableCodes));
            } catch (RuntimeException e) {
                log.warn("KYC verification reminder reservation failed for user {}: {}", id, e.getMessage());
                continue;
            }
            if (reservation != null && send(reservation)) {
                sent++;
            }
        }
        if (!candidates.isEmpty()) {
            log.info("KYC verification reminders: {} candidates, {} sent", candidates.size(), sent);
        }
    }

    /** Réserve la relance (compteur + horodatage) ; null si le compte est à ignorer. */
    private Reservation reserve(UUID id, Instant now, List<String> retryableCodes) {
        UserEntity user = userRepository.findById(id).orElse(null);
        if (user == null || !UserZones.isWithinWindow(user.getCountry(), now,
                properties.windowStartHour(), properties.windowEndHour())) {
            return null;
        }
        // La boucle peut durer : une nouvelle session ou un changement de statut annule la relance.
        if (reminderRepository.isStillCandidate(id, retryableCodes) != 1) {
            return null;
        }
        String reminderCase = reminderRepository.reminderCase(id);
        if (reminderCase == null) {
            return null;
        }
        int attempt = user.getKycReminderCount() + 1;
        user.setKycReminderCount(attempt);
        user.setKycReminderLastAt(now);
        userRepository.save(user);
        return new Reservation(id, attempt, reminderCase);
    }

    private boolean send(Reservation reservation) {
        UUID id = reservation.userId();
        try {
            var text = NotificationTexts.kycVerificationReminder(
                    notificationDispatcher.messagesFor(id), reservation.reminderCase(), reservation.attempt());
            notificationDispatcher.notifyUser(id, text.title(), text.body(), Map.of(
                    "type", NOTIFICATION_TYPE, "reminder", "true",
                    "attempt", String.valueOf(reservation.attempt()), "case", reservation.reminderCase()));
            auditService.log("USER", id, "KYC_VERIFICATION_REMINDER_SENT", id,
                    Map.of("attempt", reservation.attempt(), "case", reservation.reminderCase()));
            return true;
        } catch (RuntimeException e) {
            log.warn("KYC verification reminder failed for user {}: {}", id, e.getMessage());
            return false;
        }
    }

    private record Reservation(UUID userId, int attempt, String reminderCase) {}
}

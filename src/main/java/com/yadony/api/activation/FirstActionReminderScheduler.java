package com.yadony.api.activation;

import com.yadony.api.activation.dto.ActivationResponse.Opportunities;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Relance J+1 / J+3 des comptes au KYC vérifié sans première action (2 envois max, 10h-20h locales). */
@Component
public class FirstActionReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(FirstActionReminderScheduler.class);
    static final String NOTIFICATION_TYPE = "FIRST_ACTION_REMINDER";

    private final ActivationRepository activationRepository;
    private final UserRepository userRepository;
    private final ActivationService activationService;
    private final NotificationDispatcher notificationDispatcher;
    private final AuditService auditService;

    @Value("${yadony.activation.first-action-reminders.enabled:false}") private boolean enabled;
    @Value("${yadony.activation.first-action-reminders.first-delay:PT24H}") private Duration firstDelay;
    @Value("${yadony.activation.first-action-reminders.second-delay:PT72H}") private Duration secondDelay;
    @Value("${yadony.activation.first-action-reminders.window-start-hour:10}") private int windowStartHour;
    @Value("${yadony.activation.first-action-reminders.window-end-hour:20}") private int windowEndHour;

    public FirstActionReminderScheduler(ActivationRepository activationRepository, UserRepository userRepository,
                                        ActivationService activationService,
                                        NotificationDispatcher notificationDispatcher, AuditService auditService) {
        this.activationRepository = activationRepository;
        this.userRepository = userRepository;
        this.activationService = activationService;
        this.notificationDispatcher = notificationDispatcher;
        this.auditService = auditService;
    }

    @Scheduled(cron = "${yadony.activation.first-action-reminders.cron:0 15 * * * *}")
    @Transactional
    public void remind() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        List<UUID> candidates = activationRepository.findFirstActionReminderCandidates(
                now.minus(firstDelay), now.minus(secondDelay));
        int sent = 0;
        for (UUID id : candidates) {
            UserEntity user = userRepository.findById(id).orElse(null);
            if (user == null || !ActivationZones.isWithinWindow(user.getCountry(), now, windowStartHour, windowEndHour)) {
                continue;
            }
            int attempt = user.getFirstActionReminderCount() + 1;
            user.setFirstActionReminderCount(attempt);
            user.setFirstActionReminderLastAt(now);   // horodaté AVANT l'envoi (idempotence)
            userRepository.save(user);
            try {
                Opportunities opportunities = activationService.opportunitiesFor(user);
                String variant = variantOf(user.getIntent(), opportunities);
                var text = NotificationTexts.firstActionReminder(
                        notificationDispatcher.messagesFor(id), variant, opportunities.total());
                notificationDispatcher.notifyUser(id, text.title(), text.body(),
                        Map.of("type", NOTIFICATION_TYPE, "variant", variant));
                auditService.log("USER", id, "FIRST_ACTION_REMINDER_SENT", id,
                        Map.of("attempt", attempt, "variant", variant));
                sent++;
            } catch (RuntimeException e) {
                log.warn("First action reminder failed for user {}: {}", id, e.getMessage());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("First action reminders: {} candidates, {} sent", candidates.size(), sent);
        }
    }

    static String variantOf(String intent, Opportunities opportunities) {
        if (intent == null) {
            return "unknown";
        }
        boolean any = opportunities.total() > 0;
        if (UserIntent.TRAVELER.name().equals(intent)) {
            return any ? "traveler-packages" : "traveler-none";
        }
        return any ? "sender-trips" : "sender-none";
    }
}

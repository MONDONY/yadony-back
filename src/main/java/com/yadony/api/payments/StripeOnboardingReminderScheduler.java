package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.notifications.NotificationDispatcher;
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

/**
 * Relance les voyageurs dont l'onboarding Stripe Connect est resté inachevé.
 *
 * <p>Un compte Express créé mais jamais complété reste {@code PENDING_ONBOARDING}
 * indéfiniment : le voyageur ne peut pas accepter la carte, et rien ne le lui
 * rappelait. Il ne le découvrait qu'en butant sur le refus au moment de publier
 * un trajet — s'il y revenait un jour.
 *
 * <p>Deux relances au total, à J+1 puis J+7 après la création du compte, jamais
 * plus : passé ça, l'utilisateur a choisi, insister n'apporte rien et coûte une
 * désinscription des notifications. La cadence est portée par
 * {@code users.stripe_onboarding_last_reminder_at}, ce qui rend le job
 * idempotent — le rejouer dans la même heure ne renvoie rien.
 */
@Component
public class StripeOnboardingReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(StripeOnboardingReminderScheduler.class);

    private static final String NOTIFICATION_TYPE = "STRIPE_ONBOARDING_INCOMPLETE";
    private static final String TITLE = "Terminez la configuration de vos paiements";
    private static final String BODY =
            "Il manque quelques informations pour que vous puissiez être payé par carte. "
            + "Deux minutes suffisent.";

    private final UserRepository userRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final AuditService auditService;

    /**
     * Délais avant chaque relance, comptés depuis la création du compte Connect.
     * Externalisés pour pouvoir les resserrer en recette sans recompiler.
     */
    @Value("${yadony.stripe.onboarding-reminder.first-delay:P1D}")
    private Duration firstDelay;

    @Value("${yadony.stripe.onboarding-reminder.second-delay:P7D}")
    private Duration secondDelay;

    @Value("${yadony.stripe.onboarding-reminder.enabled:true}")
    private boolean enabled;

    public StripeOnboardingReminderScheduler(UserRepository userRepository,
                                             NotificationDispatcher notificationDispatcher,
                                             AuditService auditService) {
        this.userRepository = userRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.auditService = auditService;
    }

    /**
     * Toutes les heures : une relance à J+1 ne gagne rien à être envoyée à la
     * minute près, et un balayage horaire reste indolore grâce à l'index
     * partiel posé par V204.
     */
    @Scheduled(cron = "${yadony.stripe.onboarding-reminder.cron:0 30 * * * *}")
    @Transactional
    public void remindStaleOnboardings() {
        if (!enabled) {
            return;
        }

        Instant now = Instant.now();
        List<UserEntity> stale = userRepository.findStaleConnectOnboardings(
                now.minus(firstDelay), now.minus(secondDelay));

        if (stale.isEmpty()) {
            return;
        }

        for (UserEntity user : stale) {
            // La relance est enregistrée avant l'envoi : si le push échoue, on
            // n'insiste pas au tick suivant. Rater une relance est bénin, en
            // envoyer une par heure ne l'est pas.
            boolean isSecond = user.getStripeOnboardingLastReminderAt() != null;
            user.setStripeOnboardingLastReminderAt(now);
            userRepository.save(user);

            try {
                notificationDispatcher.notifyUser(user.getId(), TITLE, BODY,
                        Map.of("type", NOTIFICATION_TYPE));
                auditService.log("USER", user.getId(), "STRIPE_ONBOARDING_REMINDER_SENT",
                        user.getId(), Map.of("attempt", isSecond ? 2 : 1));
            } catch (RuntimeException e) {
                log.warn("Stripe onboarding reminder failed for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("Stripe onboarding reminders processed: {}", stale.size());
    }
}

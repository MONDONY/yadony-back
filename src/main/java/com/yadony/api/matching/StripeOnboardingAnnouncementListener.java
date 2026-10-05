package com.yadony.api.matching;

import com.yadony.api.payments.events.StripeOnboardingCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ouvre à la carte les trajets publiés avant la fin de l'onboarding Stripe Connect
 * (Sentry FLUTTER-DH). Voir {@link AnnouncementService#enableCardOnOpenAnnouncements}.
 *
 * <p>AFTER_COMMIT : le statut ONBOARDING_COMPLETE doit être écrit avant qu'on le relise.
 * {@code fallbackExecution} : le rafraîchissement manuel ({@code PaymentService#refreshConnectAccount})
 * publie l'événement hors transaction. Une erreur ici est journalisée sans remonter : elle ne
 * doit jamais faire échouer le webhook Stripe ni la réponse du rafraîchissement.
 */
@Component
public class StripeOnboardingAnnouncementListener {

    private static final Logger log = LoggerFactory.getLogger(StripeOnboardingAnnouncementListener.class);

    private final AnnouncementService announcementService;

    public StripeOnboardingAnnouncementListener(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStripeOnboardingCompleted(StripeOnboardingCompletedEvent event) {
        try {
            announcementService.enableCardOnOpenAnnouncements(event.userId());
        } catch (RuntimeException e) {
            log.error("Could not enable card on open announcements for user {}", event.userId(), e);
        }
    }
}

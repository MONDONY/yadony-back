package com.yadony.api.subscriptions;

import com.yadony.api.matching.AnnouncementPublishedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;

@Component
public class TravelerAvailabilityListener {

    private final TravelerSubscriptionRepository subscriptionRepository;
    private final NotificationDispatcher notificationDispatcher;

    public TravelerAvailabilityListener(TravelerSubscriptionRepository subscriptionRepository,
                                        NotificationDispatcher notificationDispatcher) {
        this.subscriptionRepository = subscriptionRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        List<TravelerSubscriptionEntity> subs =
            subscriptionRepository.findAllByTravelerId(event.travelerId());
        if (subs.isEmpty()) return;

        String title = event.travelerName() + " a publié un nouveau trajet";
        String body  = event.departureCity() + " → " + event.arrivalCity();
        Map<String, String> data = Map.of(
            "type", "TRAVELER_NEW_ANNOUNCEMENT",
            "announcementId", event.announcementId().toString(),
            "travelerId", event.travelerId().toString()
        );

        for (TravelerSubscriptionEntity sub : subs) {
            // Confidentialité — l'abonnement porte sur le contenu du voyageur : ni push, ni
            // pastille « nouveau » si les deux comptes sont masqués l'un pour l'autre. Poser
            // hasNew malgré tout afficherait un badge pour un trajet que l'abonné ne peut
            // pas ouvrir. L'abonnement lui-même n'est pas supprimé : le blocage est
            // réversible, et le retirer serait détectable côté abonné.
            boolean notified = notificationDispatcher.notifyUnlessBlocked(
                    sub.getSenderId(), event.travelerId(), title, body, data, sub.isPushEnabled());
            if (!notified) {
                continue;
            }
            sub.setHasNew(true);
            subscriptionRepository.save(sub);
        }
    }
}

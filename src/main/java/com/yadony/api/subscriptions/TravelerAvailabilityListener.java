package com.yadony.api.subscriptions;

import com.yadony.api.common.BlockVisibility;
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
    private final BlockVisibility blockVisibility;

    public TravelerAvailabilityListener(TravelerSubscriptionRepository subscriptionRepository,
                                        NotificationDispatcher notificationDispatcher,
                                        BlockVisibility blockVisibility) {
        this.subscriptionRepository = subscriptionRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.blockVisibility = blockVisibility;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        List<TravelerSubscriptionEntity> subs =
            subscriptionRepository.findAllByTravelerId(event.travelerId());
        if (subs.isEmpty()) return;

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
            // réversible, et le retirer serait détectable côté abonné. Vérifié avant de poser
            // l'indicateur, que la ligne du feed ci-dessous ne doit jamais devancer.
            if (blockVisibility.isHidden(sub.getSenderId(), event.travelerId())) {
                continue;
            }
            // Bruit : un voyageur suivi publie souvent en rafale (trajets récurrents générés
            // la nuit, étapes d'un même voyage). Le téléphone ne sonne que pour la première
            // nouveauté non vue ; les suivantes restent dans le feed jusqu'à markSeen.
            boolean firstUnseen = subscriptionRepository.markNewIfUnseen(sub.getId()) == 1;
            // Le texte se rend une fois par abonné : chacun peut avoir sa propre langue.
            var text = com.yadony.api.notifications.NotificationTexts.travelerNewAnnouncement(
                    notificationDispatcher.messagesFor(sub.getSenderId()),
                    event.travelerName(), event.departureCity(), event.arrivalCity());
            notificationDispatcher.notifyUnlessBlocked(sub.getSenderId(), event.travelerId(),
                    text.title(), text.body(), data, sub.isPushEnabled() && firstUnseen);
        }
    }
}

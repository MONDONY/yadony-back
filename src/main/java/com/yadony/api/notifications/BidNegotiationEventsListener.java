package com.yadony.api.notifications;

import com.yadony.api.matching.BidNegotiationMessageKind;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Notifications du fil de négociation d'un trajet.
 *
 * <p>Seul point de contact entre {@code matching/} et {@code notifications/} pour
 * cette fonctionnalité : {@code BidNegotiationService} ne connaît pas le dispatcher,
 * il publie des événements que ce listener traduit en push.
 */
@Component
public class BidNegotiationEventsListener {

    private final NotificationDispatcher dispatcher;

    public BidNegotiationEventsListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @EventListener
    @Async
    public void onMessagePosted(BidNegotiationMessagePostedEvent e) {
        dispatcher.notifyUser(
                e.recipientId(),
                titleFor(e.kind()),
                bodyFor(e),
                Map.of(
                        "type", "bid_negotiation_message",
                        "bidId", e.bidId().toString(),
                        "announcementId", e.announcementId().toString(),
                        "kind", e.kind().name()
                )
        );
    }

    @EventListener
    @Async
    public void onExpired(BidNegotiationExpiredEvent e) {
        Map<String, String> data = Map.of(
                "type", "bid_negotiation_expired",
                "bidId", e.bidId().toString(),
                "announcementId", e.announcementId().toString()
        );
        String body = "Faute de réponse, la discussion de prix sur ce trajet s'est refermée.";
        dispatcher.notifyUser(e.senderId(), "Discussion de prix expirée", body, data);
        dispatcher.notifyUser(e.travelerId(), "Discussion de prix expirée", body, data);
    }

    private String titleFor(BidNegotiationMessageKind kind) {
        return switch (kind) {
            case PROPOSAL -> "Nouvelle proposition de prix";
            case COUNTER -> "Nouvelle contre-proposition";
            case ACCEPT -> "Prix accepté";
            case REJECT -> "Discussion de prix close";
        };
    }

    private String bodyFor(BidNegotiationMessagePostedEvent e) {
        if (e.proposedGrossEur() == null) {
            return "La discussion de prix sur ce trajet est terminée.";
        }
        return switch (e.kind()) {
            case PROPOSAL -> String.format("Un expéditeur propose %s € pour votre trajet",
                    e.proposedGrossEur().toPlainString());
            case COUNTER -> String.format("Nouvelle offre : %s € (tour %d)",
                    e.proposedGrossEur().toPlainString(), e.round());
            case ACCEPT -> String.format("Accord trouvé à %s €",
                    e.proposedGrossEur().toPlainString());
            case REJECT -> "La discussion de prix sur ce trajet est terminée.";
        };
    }
}

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
        // Message posté par l'autre partie : supprimé si les deux comptes sont masqués
        // l'un pour l'autre. Une négociation liée à une transaction en cours passe quand
        // même, isHidden portant cette exception.
        var text = textFor(e);
        dispatcher.notifyUnlessBlocked(
                e.recipientId(),
                e.authorId(),
                text.title(),
                text.body(),
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
        var text = NotificationTexts.bidNegotiationExpired();
        // Chacun est prévenu au sujet de l'autre : si les deux comptes sont masqués l'un
        // pour l'autre, la discussion morte n'a plus à être annoncée. Aucun risque de
        // couper une coordination, une négociation expirée ne liant plus personne.
        dispatcher.notifyUnlessBlocked(e.senderId(), e.travelerId(), text.title(), text.body(), data);
        dispatcher.notifyUnlessBlocked(e.travelerId(), e.senderId(), text.title(), text.body(), data);
    }

    private static NotificationText textFor(BidNegotiationMessagePostedEvent e) {
        // Sans montant, quel que soit le genre, la discussion est close.
        if (e.proposedGrossEur() == null) {
            return e.kind() == BidNegotiationMessageKind.REJECT
                    ? NotificationTexts.bidNegotiationClosed()
                    : new NotificationText(titleFor(e.kind()), NotificationTexts.bidNegotiationClosed().body());
        }
        String gross = e.proposedGrossEur().toPlainString();
        return switch (e.kind()) {
            case PROPOSAL -> NotificationTexts.bidNegotiationProposal(gross);
            case COUNTER -> NotificationTexts.bidNegotiationCounter(gross, e.round());
            case ACCEPT -> NotificationTexts.bidNegotiationAccepted(gross);
            case REJECT -> NotificationTexts.bidNegotiationClosed();
        };
    }

    private static String titleFor(BidNegotiationMessageKind kind) {
        return switch (kind) {
            case PROPOSAL -> NotificationTexts.bidNegotiationProposal("0").title();
            case COUNTER -> NotificationTexts.bidNegotiationCounter("0", 1).title();
            case ACCEPT -> NotificationTexts.bidNegotiationAccepted("0").title();
            case REJECT -> NotificationTexts.bidNegotiationClosed().title();
        };
    }
}

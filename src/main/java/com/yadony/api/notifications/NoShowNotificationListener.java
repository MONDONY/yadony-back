package com.yadony.api.notifications;

import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.NoShowAdminDecision;
import com.yadony.api.cancellation.events.NoShowAdminDecisionEvent;
import com.yadony.api.cancellation.events.SenderNoShowReportedEvent;
import com.yadony.api.common.i18n.Messages;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Notifications du cycle de vie d'une déclaration de no-show, chacune dans la langue de
 * son destinataire, après commit (une déclaration ou une décision annulée n'annonce rien) :
 * <ul>
 *   <li>l'expéditeur déclaré absent à la remise est prévenu qu'il peut contester ;</li>
 *   <li>la décision de l'administration (confirmée / rejetée) est notifiée aux deux parties,
 *       sans le motif interne de l'admin.</li>
 * </ul>
 * Les deux ouvrent le détail du colis ({@code bids/{id}}, cf. {@link NotificationDeeplink}).
 */
@Component
public class NoShowNotificationListener {

    static final String TYPE_REPORTED = "SENDER_NOSHOW_REPORTED";
    static final String TYPE_DECISION = "NOSHOW_DECISION";

    private final NotificationDispatcher dispatcher;

    public NoShowNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSenderNoShowReported(SenderNoShowReportedEvent event) {
        if (event.senderId() == null) return;
        NotificationText text = NotificationTexts.senderNoShowReported(
                dispatcher.messagesFor(event.senderId()), event.contestationHours());
        dispatcher.notifyUser(event.senderId(), text.title(), text.body(),
                Map.of("type", TYPE_REPORTED, "bidId", event.bidId().toString(),
                        "cancellationId", event.cancellationId().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDecision(NoShowAdminDecisionEvent event) {
        Function<Messages, NotificationText> textFor = textFor(event);
        Map<String, String> data = Map.of("type", TYPE_DECISION, "bidId", event.bidId().toString(),
                "cancellationId", event.cancellationId().toString(), "decision", event.decision().name());
        notify(event.senderId(), textFor, data);
        notify(event.travelerId(), textFor, data);
    }

    private static Function<Messages, NotificationText> textFor(NoShowAdminDecisionEvent event) {
        if (event.decision() == NoShowAdminDecision.REJECTED) return NotificationTexts::noShowRejected;
        return event.scope() == CancellationScope.DELIVERY
                ? NotificationTexts::noShowConfirmedDelivery
                : NotificationTexts::noShowConfirmedHandover;
    }

    private void notify(UUID userId, Function<Messages, NotificationText> textFor, Map<String, String> data) {
        if (userId == null) return;
        NotificationText text = textFor.apply(dispatcher.messagesFor(userId));
        dispatcher.notifyUser(userId, text.title(), text.body(), data);
    }
}

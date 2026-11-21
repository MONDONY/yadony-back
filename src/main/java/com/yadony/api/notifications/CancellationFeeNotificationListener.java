package com.yadony.api.notifications;

import com.yadony.api.cancellation.events.CancellationFeePaidOutEvent;
import com.yadony.api.cancellation.events.SenderCancellationFeeRetainedEvent;
import com.yadony.api.cancellation.fee.CancellationFeeReason;
import com.yadony.api.common.i18n.Messages;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Frais d'annulation d'un expéditeur fautif, dans la langue de chaque destinataire, in-app et push :
 * <ul>
 *   <li>l'EXPÉDITEUR apprend le montant retenu et le montant rendu quand la retenue est réglée (carte comme mobile
 *       money). Indispensable après une absence confirmée : aucun aperçu ne lui a été montré ;</li>
 *   <li>le VOYAGEUR apprend que son dédommagement net est versé.</li>
 * </ul>
 * Les montants viennent de l'événement lui-même (ils vivent dans {@code cancellation.fee}), formatés comme partout
 * ailleurs ({@link NotificationTexts#amount}). AFTER_COMMIT : un règlement ou un versement défait n'annonce rien.
 *
 * <p>Une seule notification par passage d'état : les deux événements ne sont publiés qu'au passage
 * {@code DUE -> RETAINED} et vers {@code PAID_OUT}, sous le verrou de la ligne ; un rejeu ne republie rien. Le
 * dispatcher n'a pas de déduplication propre : c'est cette garantie à la source qui l'évite. Rien n'est envoyé pour un
 * montant nul (rien retenu, rien versé).
 */
@Component
public class CancellationFeeNotificationListener {

    static final String TYPE_RETAINED = "CANCELLATION_FEE_RETAINED";
    static final String TYPE_PAID_OUT = "CANCELLATION_FEE_PAID_OUT";

    private final NotificationDispatcher dispatcher;

    public CancellationFeeNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRetained(SenderCancellationFeeRetainedEvent event) {
        if (event.senderId() == null || !positive(event.retainedAmount())) {
            return;
        }
        Messages m = dispatcher.messagesFor(event.senderId());
        NotificationText text = NotificationTexts.cancellationFeeRetained(m,
                event.reason() == CancellationFeeReason.SENDER_NO_SHOW,
                NotificationTexts.amount(event.retainedAmount(), event.currency()),
                NotificationTexts.amount(event.refundedAmount(), event.currency()));
        dispatcher.notifyUser(event.senderId(), text.title(), text.body(), data(TYPE_RETAINED, event.bidId().toString(),
                event.currency()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaidOut(CancellationFeePaidOutEvent event) {
        if (event.travelerId() == null || !positive(event.travelerNet())) {
            return;
        }
        Messages m = dispatcher.messagesFor(event.travelerId());
        NotificationText text = NotificationTexts.cancellationFeePaidOut(m,
                NotificationTexts.amount(event.travelerNet(), event.currency()));
        dispatcher.notifyUser(event.travelerId(), text.title(), text.body(), data(TYPE_PAID_OUT,
                event.bidId().toString(), event.currency()));
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    private static Map<String, String> data(String type, String bidId, String currency) {
        return Map.of("type", type, "bidId", bidId, "currency", String.valueOf(currency));
    }
}

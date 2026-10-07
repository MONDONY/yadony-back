package com.yadony.api.payments;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Sender no-show confirmé (admin ou timeout de contestation) : si le bid portait un
 * escrow Stripe, le rembourser à l'expéditeur — le colis n'a pas voyagé.
 * (Le pendant cash — remboursement de commission au voyageur — vit dans
 * payments/cash/CommissionRefundListener.)
 *
 * <p>Le remboursement effectif (cancel PENDING / refund ESCROW / no-op) est délégué à
 * {@link RefundProcessor} (REQUIRES_NEW par paiement) — pas de @Transactional ici.
 */
@Component
public class SenderNoShowConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(SenderNoShowConfirmedListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;

    public SenderNoShowConfirmedListener(PaymentRepository paymentRepository,
                                         RefundProcessor refundProcessor) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) {
            return;
        }
        paymentRepository.findForBid(event.bidId()).ifPresentOrElse(
                payment -> refundProcessor.processRefund(payment.getId(),
                        "PAYMENT_REFUNDED_SENDER_NO_SHOW", event.bidId(),
                        Map.of("bidId", event.bidId().toString(), "reason", "sender_no_show")),
                () -> log.debug("Sender no-show bid {} : pas de paiement Stripe — rien à rembourser",
                        event.bidId()));
    }
}

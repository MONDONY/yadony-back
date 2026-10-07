package com.yadony.api.payments;

import com.yadony.api.matching.events.BidRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Bid rejeté par l'expéditeur : annule (PENDING) ou rembourse (ESCROW) le paiement.
 * Toute la mécanique transactionnelle/idempotence vit dans {@link RefundProcessor}
 * (REQUIRES_NEW par paiement) — pas de @Transactional ici.
 */
@Component
public class BidRejectedEventListener {

    private static final Logger log = LoggerFactory.getLogger(BidRejectedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;

    public BidRejectedEventListener(PaymentRepository paymentRepository,
                                    RefundProcessor refundProcessor) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleBidRejected(BidRejectedEvent event) {
        paymentRepository.findForBid(event.getBidId()).ifPresentOrElse(
                payment -> refundProcessor.processRefund(
                        payment.getId(),
                        "PAYMENT_REFUNDED_BID_REJECTED",
                        event.getBidId(),
                        Map.of("reason", "bid_rejected")),
                () -> log.debug("Aucun paiement pour bid {} — rien à rembourser", event.getBidId()));
    }
}

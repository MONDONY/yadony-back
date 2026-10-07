package com.yadony.api.payments;

import com.yadony.api.matching.events.ParcelRefusedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Story 9.4 — Colis refusé par le voyageur : annule (PENDING) ou rembourse (ESCROW)
 * le paiement. Un paiement RELEASED n'est jamais remboursé (no-op côté processor).
 * Toute la mécanique transactionnelle/idempotence vit dans {@link RefundProcessor}.
 */
@Component
public class ParcelRefusedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ParcelRefusedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;

    public ParcelRefusedEventListener(PaymentRepository paymentRepository,
                                      RefundProcessor refundProcessor) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onParcelRefused(ParcelRefusedEvent event) {
        paymentRepository.findForBid(event.getBidId()).ifPresentOrElse(
                payment -> refundProcessor.processRefund(
                        payment.getId(),
                        "PAYMENT_REFUNDED_PARCEL_REFUSED",
                        event.getTravelerId(),
                        Map.of("bidId", event.getBidId().toString(),
                                "reason", event.getReason() != null ? event.getReason() : "")),
                () -> log.debug("Aucun paiement pour colis refusé bid={} — rien à rembourser",
                        event.getBidId()));
    }
}

package com.yadony.api.payments;

import com.yadony.api.matching.events.VoyageurNoShowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Story 9.6 — Voyageur no-show : rembourse l'escrow du paiement (ou annule le PI
 * PENDING). Toute la mécanique transactionnelle/idempotence vit dans
 * {@link RefundProcessor} (REQUIRES_NEW par paiement) — pas de @Transactional ici.
 */
@Component
public class NoShowEventListener {

    private static final Logger log = LoggerFactory.getLogger(NoShowEventListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;

    public NoShowEventListener(PaymentRepository paymentRepository,
                               RefundProcessor refundProcessor) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        paymentRepository.findForBid(event.getBidId()).ifPresentOrElse(
                payment -> refundProcessor.processRefund(
                        payment.getId(),
                        "PAYMENT_REFUNDED_NO_SHOW",
                        event.getTravelerId(),
                        Map.of("bidId", event.getBidId().toString())),
                () -> log.debug("Aucun paiement pour no-show bid={} — rien à rembourser",
                        event.getBidId()));
    }
}

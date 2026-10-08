package com.yadony.api.payments;

import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Bid expiré au départ du voyageur, ou à la date limite de dépôt (FLUTTER-GA) : annule
 * (PENDING) ou rembourse intégralement (ESCROW) le paiement.
 * Toute la mécanique transactionnelle/idempotence vit dans {@link RefundProcessor}
 * (REQUIRES_NEW par paiement) — pas de @Transactional ici.
 */
@Component
public class BidExpiredOnDepartureEventListener {

    private static final Logger log = LoggerFactory.getLogger(BidExpiredOnDepartureEventListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;

    public BidExpiredOnDepartureEventListener(PaymentRepository paymentRepository,
                                              RefundProcessor refundProcessor) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleBidExpired(BidExpiredOnDepartureEvent event) {
        paymentRepository.findForBid(event.getBidId()).ifPresentOrElse(
                payment -> refundProcessor.processRefund(
                        payment.getId(),
                        "PAYMENT_REFUNDED_BID_EXPIRED",
                        event.getBidId(),
                        Map.of("reason", event.isTripDeparted()
                                ? "bid_expired_traveler_departed"
                                : "bid_expired_handover_deadline")),
                () -> log.debug("Aucun paiement pour bid expiré {} — rien à rembourser",
                        event.getBidId()));
    }
}

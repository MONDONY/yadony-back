package com.yadony.api.payments;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 6.7 — écoute {@link TripCancelledEvent} et rembourse chaque bid ayant un paiement
 * (ESCROW remboursé, PENDING annulé) via {@link RefundProcessor}.
 *
 * <p>Chaque bid est délégué à {@code refundProcessor.processRefund} qui ouvre sa propre
 * transaction {@code REQUIRES_NEW} : un échec Stripe sur un bid (try/catch ici) n'abandonne
 * pas les remboursements des autres bids du lot. Ce listener ne porte donc plus de
 * {@code @Transactional} de méthode.
 */
@Component
public class TripCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(TripCancelledEventListener.class);

    private final PaymentRepository paymentRepository;
    private final RefundProcessor refundProcessor;

    public TripCancelledEventListener(PaymentRepository paymentRepository,
                                      RefundProcessor refundProcessor) {
        this.paymentRepository = paymentRepository;
        this.refundProcessor = refundProcessor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTripCancelled(TripCancelledEvent event) {
        List<UUID> bidIds = event.getAffectedBidIds();
        if (bidIds == null || bidIds.isEmpty()) {
            return;
        }

        log.info("TripCancelledEvent: annonce={}, {} bid(s) à rembourser",
                event.getAnnouncementId(), bidIds.size());

        for (UUID bidId : bidIds) {
            try {
                // Négociation / trajet dédié : l'escrow est keyé sur le thread (bid_id NULL) ;
                // findForBid se rabat sur le paiement du fil lié.
                Optional<PaymentEntity> paymentOpt = paymentRepository.findForBid(bidId);

                if (paymentOpt.isEmpty()) {
                    log.debug("Pas de paiement pour bid {} — skip", bidId);
                    continue;
                }

                refundProcessor.processRefund(paymentOpt.get().getId(), "PAYMENT_REFUNDED",
                        bidId, Map.of("bidId", bidId.toString(), "reason", "trip_cancelled"));
            } catch (Exception e) {
                log.error("Échec remboursement bid {} (trip cancelled) : {}", bidId, e.getMessage(), e);
            }
        }
    }
}

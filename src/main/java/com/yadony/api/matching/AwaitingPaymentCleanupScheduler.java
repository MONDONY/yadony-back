package com.yadony.api.matching;

import com.yadony.api.payments.PaymentService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Toutes les 5 minutes, supprime physiquement les bids AWAITING_PAYMENT dont la fenêtre
 * de paiement (15 min après création) est dépassée. Annule le PaymentIntent associé pour
 * libérer le hold sur la carte de l'expéditeur (0 frais Stripe).
 *
 * Race condition gérée : si le webhook Stripe arrive au même moment et que la capture
 * a déjà eu lieu (Stripe répond payment_intent_unexpected_state), on promeut le bid en
 * PENDING au lieu de le supprimer.
 */
@Component
public class AwaitingPaymentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AwaitingPaymentCleanupScheduler.class);

    private final BidRepository bidRepository;
    private final PaymentService paymentService;

    public AwaitingPaymentCleanupScheduler(BidRepository bidRepository,
                                           PaymentService paymentService) {
        this.bidRepository = bidRepository;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void cleanupUnpaidBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BidEntity> expired = bidRepository
            .findByStatusAndAwaitingPaymentExpiresAtBefore(BidStatus.AWAITING_PAYMENT, now);

        for (BidEntity bid : expired) {
            // Un bid mobile money AWAITING_PAYMENT n'a pas de PaymentIntent : son expiration
            // (annulation + restitution de capacité + notifications) est portée par
            // MobileMoneyPaymentDeadlineScheduler.
            if (bid.getPaymentMethod() == com.yadony.api.payments.cash.PaymentMethod.MOBILE_MONEY) {
                continue;
            }
            String piId = bid.getPaymentIntentId();
            try {
                paymentService.cancelPaymentIntent(piId);
                bid.softDelete();
                bidRepository.save(bid);
                log.info("Bid {} (PI={}) soft-deleted (unpaid timeout)", bid.getId(), piId);
            } catch (StripeException e) {
                if ("payment_intent_unexpected_state".equals(e.getCode())) {
                    handlePaymentIntentUnexpectedState(bid, piId);
                } else {
                    log.error("Cleanup failed for bid {} (PI={}): {}",
                        bid.getId(), piId, e.getMessage());
                    // Do not delete — retry next tick
                }
            }
        }
    }

    private void handlePaymentIntentUnexpectedState(BidEntity bid, String piId) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(piId);
            String status = pi.getStatus();

            if ("succeeded".equals(status) || "requires_capture".equals(status)
                || "processing".equals(status)) {
                // Payment is authorized/captured — promote bid to PENDING
                log.warn("Race condition for bid {}: PI status={} — promoting to PENDING",
                    bid.getId(), status);
                paymentService.promoteBidOnPaymentAuthorized(piId);
            } else if ("canceled".equals(status) || "failed".equals(status)) {
                // Payment was cancelled or failed — soft-delete the bid
                bid.softDelete();
                bidRepository.save(bid);
                log.info("Bid {} (PI={}) soft-deleted (PI status: {})", bid.getId(), piId, status);
            } else {
                // Unknown state — log and retry
                log.warn("Bid {} (PI={}): unexpected PI status={} — will retry",
                    bid.getId(), piId, status);
            }
        } catch (StripeException e) {
            log.error("Could not handle payment_intent_unexpected_state for bid {} (PI={}): {}",
                bid.getId(), piId, e.getMessage());
        }
    }

}

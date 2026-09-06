package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Sur acceptation d'un bid par le voyageur :
 * - Pour les bids CASH : la commission est désormais prélevée de façon synchrone dans
 *   CashCommissionService.acceptCashBid (wallet-first puis carte en fallback).
 *   Ce listener n'intervient plus pour les bids CASH.
 * - Pour les paiements non-legacy (separate charges and transfers) → re-vérifie l'éligibilité
 *   du voyageur puis capture le PaymentIntent sur le compte plateforme. L'argent reste là
 *   jusqu'à confirmation de livraison (où DeliveryEventListener fera Transfer.create).
 * - Pour les paiements legacy (transfer_data.destination déjà posé) → ne rien faire ici.
 *   La capture historique reste à la livraison.
 */
@Component
public class BidAcceptedEventListener {

    private static final Logger log = LoggerFactory.getLogger(BidAcceptedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;

    public BidAcceptedEventListener(PaymentRepository paymentRepository,
                                    AuditService auditService,
                                    UserRepository userRepository,
                                    BidRepository bidRepository) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidAccepted(BidAcceptedEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("BidAccepted event for unknown bid {}", event.getBidId());
            return;
        }

        // CASH bids : commission traitée en synchrone dans CashCommissionService.acceptCashBid.
        // MOBILE_MONEY bids : rail pawaPay entièrement porté par MobileMoneyBidPaymentService —
        // aucun PaymentIntent Stripe n'existe jamais pour ces paiements. Ce listener ne fait
        // rien pour éviter tout risque de double prélèvement (CASH) ou une exception sur un
        // PaymentIntent inexistant (MOBILE_MONEY).
        //
        // Avant cette garde explicite, un bid mobile money n'échappait au
        // chemin STRIPE ci-dessous que par coïncidence — payment.getStatus() != ESCROW au
        // moment précis où CE listener asynchrone le lisait. Sous un pool @Async saturé, nul
        // besoin d'attendre la confirmation d'escrow mobile money pour le déclencher
        // : n'importe quel retard suffisant fait passer ce listener sur la branche
        // « traveler.getStripeAccountStatus() != ONBOARDING_COMPLETE » (vrai pour TOUT
        // voyageur mobile money, qui n'a jamais de compte Stripe Connect) et tenter
        // PaymentIntent.retrieve(null) — une exception en pleine transaction REQUIRES_NEW sur
        // le chemin de l'argent, pour un bid que ce listener n'a jamais eu à traiter.
        if (bid.getPaymentMethod() == com.yadony.api.payments.cash.PaymentMethod.CASH
                || bid.getPaymentMethod() == com.yadony.api.payments.cash.PaymentMethod.MOBILE_MONEY) {
            return;
        }

        // --- STRIPE bid: capture PaymentIntent ---
        Optional<PaymentEntity> opt = paymentRepository.findByBidId(event.getBidId());
        if (opt.isEmpty()) {
            log.warn("BidAccepted but no payment found for bid {}", event.getBidId());
            return;
        }
        PaymentEntity payment = opt.get();

        if (payment.isLegacyDestinationCharge()) {
            log.info("Bid {} accepted but payment is legacy — capture deferred to delivery",
                    event.getBidId());
            return;
        }

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Bid {} accepted but payment status is {} — skipping capture",
                    event.getBidId(), payment.getStatus());
            return;
        }

        UserEntity traveler = userRepository.findById(event.getTravelerId()).orElse(null);
        if (traveler == null || traveler.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
            log.warn("Bid {} cancelled: traveler {} lost Connect eligibility before capture",
                    event.getBidId(), event.getTravelerId());
            String piId = payment.getStripePaymentIntentId();
            try {
                PaymentIntent pi = PaymentIntent.retrieve(piId);
                pi.cancel();
                log.info("PaymentIntent {} cancelled due to traveler ineligibility (bid {})",
                        piId, event.getBidId());

                payment.setStatus(PaymentStatus.CANCELLED);
                paymentRepository.save(payment);

                bid.setStatus(BidStatus.CANCELLED);
                bidRepository.save(bid);
                auditService.log("BID", bid.getId(), "BID_CANCELLED_TRAVELER_INELIGIBLE",
                        event.getTravelerId(),
                        Map.of("reason", "traveler-connect-ineligible",
                                "piId", piId));
            } catch (StripeException cancelEx) {
                log.error("Could not cancel PI {} for ineligible traveler {} on bid {}",
                        piId, event.getTravelerId(), event.getBidId(), cancelEx);
                auditService.log("BID", bid.getId(), "BID_CANCEL_PI_FAILED",
                        event.getTravelerId(),
                        Map.of("pi_id", piId,
                                "traveler_id", event.getTravelerId().toString(),
                                "reason", "traveler_not_eligible_pi_cancel_failed"));
            }
            return;
        }

        try {
            int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now());
            if (updated == 0) {
                log.info("Payment {} already captured or not in ESCROW — skipping", payment.getId());
                return;
            }

            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.capture();

            auditService.log("PAYMENT", payment.getId(), "PAYMENT_CAPTURED_ON_PLATFORM",
                    payment.getBidId(),
                    Map.of("piId", payment.getStripePaymentIntentId(),
                            "bidId", event.getBidId().toString()));

            log.info("PaymentIntent {} captured on platform for bid {}",
                    payment.getStripePaymentIntentId(), event.getBidId());

        } catch (StripeException e) {
            log.error("Capture failed for bid {} (PI={}): {}",
                    event.getBidId(), payment.getStripePaymentIntentId(),
                    e.getMessage(), e);
        }
    }
}

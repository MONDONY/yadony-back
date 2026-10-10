package com.yadony.api.payments;

import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


/**
 * Negotiation / dedicated-trip Stripe escrow — capture onto the platform balance.
 *
 * <p>The classic bid flow captures the manual-capture {@code PaymentIntent} at acceptance
 * ({@link BidAcceptedEventListener}) and only transfers to the traveler at delivery
 * ({@link DeliveryEventListener}). The negotiation flow stores its escrow payment on the
 * negotiation thread ({@code bid_id = NULL}) and never published a {@code BidAcceptedEvent},
 * so the capture step never ran — the PaymentIntent stayed an authorization hold that
 * expires (~7 days) and the money never reached the traveler.
 *
 * <p><b>Trigger.</b> This listens to {@link PaymentEscrowReadyEvent}, which is published by
 * {@code PaymentService.handlePaymentEscrowActive} at the exact moment a payment transitions
 * {@code PENDING → ESCROW} (the Stripe {@code amount_capturable_updated} webhook = funds held,
 * PI {@code requires_capture}). We deliberately do NOT key off {@code PackageRequestAcceptedEvent}:
 * the synchronous {@code /checkout} can publish that BEFORE the webhook flips the payment to
 * ESCROW (observed ~7s earlier), so the capture would race and be skipped.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} guarantees the ESCROW status is committed
 * before we run.
 *
 * <p>Only thread-keyed payments are handled here ({@code negotiation_thread_id} set,
 * {@code bid_id} null). Classic bid payments emit the same event but are captured by
 * {@link BidAcceptedEventListener}; we skip them to avoid a double capture.
 *
 * <p>The capture itself is delegated to {@link EscrowCaptureService} (shared with the delivery
 * release and the admin force-release).
 *
 * <p>After capture the payment stays {@code ESCROW} (only {@code captured_at} is set), so
 * {@link DeliveryEventListener} still transfers it to the traveler at delivery — with no
 * card-authorization expiry, since the funds already left the card at acceptance.
 */
@Component
public class NegotiationCaptureListener {

    private static final Logger log = LoggerFactory.getLogger(NegotiationCaptureListener.class);

    private final PaymentRepository paymentRepository;
    private final EscrowCaptureService escrowCapture;

    public NegotiationCaptureListener(PaymentRepository paymentRepository,
                                      EscrowCaptureService escrowCapture) {
        this.paymentRepository = paymentRepository;
        this.escrowCapture = escrowCapture;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEscrowReady(PaymentEscrowReadyEvent event) {
        if (event.isCallerSettles()) {
            // La resynchronisation d'un colis déjà livré capture puis verse elle-même, juste après
            // le commit : capturer ici en parallèle ne ferait que courir avec elle.
            return;
        }
        PaymentEntity payment = paymentRepository.findById(event.getPaymentId()).orElse(null);
        if (payment == null) {
            return;
        }

        // Only the negotiation/dedicated-trip escrow (keyed on the thread) is captured here.
        // Classic bid payments (bid_id set) are captured at acceptance by BidAcceptedEventListener.
        if (payment.getNegotiationThreadId() == null || payment.getBidId() != null) {
            return;
        }
        if (payment.isLegacyDestinationCharge()) {
            // Legacy destination-charge model captures at delivery — leave it untouched.
            return;
        }
        if (payment.getStatus() != PaymentStatus.ESCROW) {
            // Not (yet) a held escrow — nothing to capture.
            return;
        }

        // Capture partagée avec la livraison et la libération admin : garde markCapturedIfEscrow,
        // clé d'idempotence capture-<paymentId>, montant attendu, audit, captured_at annulé si
        // Stripe refuse (auparavant il restait posé sur un PaymentIntent jamais capturé), et
        // alerte admin ESCROW_CAPTURE_FAILED_<id> en cas d'échec.
        try {
            EscrowCaptureService.Outcome outcome =
                    escrowCapture.ensureCaptured(payment.getId(), "negotiation-escrow-ready");
            log.info("Negotiation PI {} {} on platform for thread {}", payment.getStripePaymentIntentId(),
                    outcome.capturedNow() ? "captured" : "already captured", payment.getNegotiationThreadId());
        } catch (EscrowCaptureService.EscrowCaptureException ex) {
            // Not rethrown — the delivery release path (which captures first) and the admin
            // force-release remain backstops; the daily reconciliation flags SEQUESTRE_NON_CAPTURE.
            log.error("Negotiation capture failed (thread={}, pi={}): {}",
                    payment.getNegotiationThreadId(), payment.getStripePaymentIntentId(), ex.getMessage());
        }
    }
}

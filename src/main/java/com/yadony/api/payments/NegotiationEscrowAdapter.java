package com.yadony.api.payments;

import com.yadony.api.requests.NegotiationEscrowPort;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Verifies, for the synchronous negotiation {@code /checkout}, that an online
 * (Stripe) PaymentIntent presented by the sender is a genuine, currently-held
 * escrow bound to the thread. See {@link NegotiationEscrowPort}.
 */
@Component
public class NegotiationEscrowAdapter implements NegotiationEscrowPort {

    private static final Logger log = LoggerFactory.getLogger(NegotiationEscrowAdapter.class);

    /** Manual-capture PaymentIntent state where the card is authorized and funds are held. */
    private static final String STATUS_ESCROW_HELD = "requires_capture";

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public NegotiationEscrowAdapter(PaymentRepository paymentRepository, PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    @Override
    public boolean verifyNegotiationEscrow(UUID threadId, String paymentIntentId) {
        if (threadId == null || paymentIntentId == null || paymentIntentId.isBlank()) {
            return false;
        }
        PaymentEntity payment = paymentRepository.findByNegotiationThreadId(threadId).orElse(null);
        if (payment == null) {
            return false; // no server-created escrow for this thread
        }
        // The client must present exactly the PaymentIntent the server created (via
        // createNegotiationEscrow) for THIS thread. This binds the id to the thread,
        // sender and agreed amount — all set server-side — so a forged or
        // someone-else's id cannot match.
        if (!paymentIntentId.equals(payment.getStripePaymentIntentId())) {
            return false;
        }
        try {
            PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
            // requires_capture is the authoritative "card authorized, funds held"
            // signal — true as soon as the sender confirms, even before the
            // amount_capturable_updated webhook lands. A PaymentIntent that was
            // created but never confirmed (requires_payment_method/confirmation) or
            // already captured/canceled is rejected.
            if (pi == null || !STATUS_ESCROW_HELD.equals(pi.getStatus())) {
                return false;
            }
            // Defense-in-depth: pin the authorized amount to the agreed price stored
            // on the server-created escrow. The thread-bound PI id already guarantees
            // binding; this additionally guards a sender from authorizing a cheaper
            // PaymentIntent should any future re-confirmation path ever be introduced.
            if (payment.getAmount() == null || pi.getAmount() == null) {
                return false;
            }
            long expectedCents = payment.getAmount()
                .multiply(java.math.BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
            if (pi.getAmount() != expectedCents) {
                return false;
            }
            // Autorisation confirmée par Stripe : le paiement est en séquestre dès maintenant, sans
            // attendre un webhook qui n'arrive pas toujours (aucun reçu en staging). Sinon il restait
            // PENDING, la livraison ne le libérait pas et le voyageur n'était jamais payé.
            paymentService.promoteNegotiationEscrowIfPending(threadId, paymentIntentId, pi.getLatestCharge());
            return true;
        } catch (StripeException e) {
            log.warn("Negotiation escrow verify failed (thread={}, pi={}): {}",
                threadId, paymentIntentId, e.getMessage());
            return false; // fail closed
        }
    }

    @Override
    public boolean releaseEscrowForMethodSwitch(UUID threadId) {
        // Delegates to PaymentService — the canonical owner of Stripe escrow
        // lifecycle (cancel PaymentIntent + flip PaymentEntity → CANCELLED + audit).
        return paymentService.cancelNegotiationEscrow(threadId, "payment-method-switch");
    }
}

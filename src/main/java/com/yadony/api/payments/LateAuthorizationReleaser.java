package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Autorisation carte arrivée APRÈS la mort du paiement (annulé avant paiement par l'expéditeur,
 * délai écoulé, changement de moyen de paiement) : l'argent ne doit jamais rester bloqué.
 *
 * <p>Le PaymentIntent est relu chez Stripe (jamais l'état porté par l'événement, qui peut dater) :
 * <ul>
 *   <li>{@code requires_capture} → autorisation libérée ({@code cancel}, motif {@code abandoned}) ;
 *       la carte n'est pas débitée ;</li>
 *   <li>{@code succeeded} (capturé, cas qui ne devrait pas exister) → remboursement intégral par la
 *       même clé d'idempotence que {@link RefundProcessor} ({@code refund-<paymentId>}), paiement
 *       {@code CANCELLED → REFUNDED}, alerte admin ;</li>
 *   <li>{@code canceled} → rien à faire (idempotent : déjà libéré, souvent par l'annulation même).</li>
 * </ul>
 * Échec Stripe : exception, le webhook est rejoué ; une capture non remboursée lève l'alerte.
 */
@Component
public class LateAuthorizationReleaser {

    private static final Logger log = LoggerFactory.getLogger(LateAuthorizationReleaser.class);

    /** {@code admin_alerts.type} ≤ 60 : préfixe (20) + UUID (36). */
    static final String REFUND_ALERT_PREFIX = "LATE_CAPTURE_REFUND_";

    static final String AUDIT_RELEASED = "LATE_AUTHORIZATION_RELEASED";
    static final String AUDIT_REFUNDED = "LATE_CAPTURE_REFUNDED";

    private final StripeGateway stripeGateway;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertEscalator alerts;

    public LateAuthorizationReleaser(StripeGateway stripeGateway, PaymentRepository paymentRepository,
                                     AuditService auditService, AdminAlertEscalator alerts) {
        this.stripeGateway = stripeGateway;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.alerts = alerts;
    }

    /** @return vrai si une action Stripe a été menée (libération ou remboursement). */
    public boolean release(UUID paymentId, String paymentIntentId, UUID bidId) {
        PaymentIntent pi;
        try {
            pi = stripeGateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            throw new IllegalStateException("Lecture du PI " + paymentIntentId + " impossible", e);
        }
        String status = pi.getStatus();
        Map<String, Object> payload = new HashMap<>();
        payload.put("piId", paymentIntentId);
        payload.put("bidId", String.valueOf(bidId));
        payload.put("stripeStatus", status);
        switch (status) {
            case "requires_capture" -> {
                try {
                    pi.cancel(PaymentIntentCancelParams.builder()
                            .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                            .build());
                } catch (StripeException e) {
                    throw new IllegalStateException("Libération du PI " + paymentIntentId + " impossible", e);
                }
                auditService.log("PAYMENT", paymentId, AUDIT_RELEASED, null, payload);
                log.warn("Autorisation tardive libérée : paiement {} déjà mort, PI {} annulé", paymentId,
                        paymentIntentId);
                return true;
            }
            case "succeeded" -> {
                payload.put("alert", REFUND_ALERT_PREFIX + paymentId);
                try {
                    Refund.create(RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                            RequestOptions.builder().setIdempotencyKey("refund-" + paymentId).build());
                } catch (StripeException e) {
                    alerts.raiseOnce(REFUND_ALERT_PREFIX + paymentId,
                            "Paiement " + paymentId + " annulé mais capturé chez Stripe (PI " + paymentIntentId
                                    + ") : remboursement automatique en échec, à rembourser à la main",
                            payload);
                    throw new IllegalStateException("Remboursement du PI " + paymentIntentId + " impossible", e);
                }
                paymentRepository.markRefundedIfCancelled(paymentId);
                auditService.log("PAYMENT", paymentId, AUDIT_REFUNDED, null, payload);
                alerts.raiseOnce(REFUND_ALERT_PREFIX + paymentId,
                        "Paiement " + paymentId + " annulé mais capturé chez Stripe (PI " + paymentIntentId
                                + ") : remboursement intégral émis, à vérifier",
                        payload);
                return true;
            }
            default -> {
                log.info("Paiement {} mort, PI {} en {} : rien à libérer", paymentId, paymentIntentId, status);
                return false;
            }
        }
    }
}

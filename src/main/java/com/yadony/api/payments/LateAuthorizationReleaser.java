package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
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
 *   <li>{@code requires_capture} → autorisation libérée ({@code cancel}, motif {@code abandoned}),
 *       que le paiement soit {@code CANCELLED} ou {@code REFUNDED} ; la carte n'est pas débitée ;</li>
 *   <li>{@code succeeded} sur un paiement {@code CANCELLED} (capturé, cas qui ne devrait pas
 *       exister) → si la charge est déjà intégralement remboursée, simple réalignement en
 *       {@code REFUNDED} ; sinon remboursement par la même clé d'idempotence que
 *       {@link RefundProcessor} ({@code refund-<paymentId>}), paiement {@code CANCELLED → REFUNDED},
 *       alerte admin ;</li>
 *   <li>{@code succeeded} sur un paiement {@code REFUNDED} → rien : il a déjà été remboursé par le
 *       chemin normal, un nouveau Refund serait un double remboursement ;</li>
 *   <li>{@code canceled} et autres → rien (idempotent).</li>
 * </ul>
 *
 * <p><b>Ne propage jamais d'exception</b> : le paiement est dans un état terminal, rejouer le
 * webhook n'y changerait rien et un 500 ferait boucler Stripe. Les échecs sont journalisés ; seul
 * un remboursement réellement nécessaire et en échec lève l'alerte unique
 * {@value #REFUND_ALERT_PREFIX}{@code <paymentId>}.
 */
@Component
public class LateAuthorizationReleaser {

    private static final Logger log = LoggerFactory.getLogger(LateAuthorizationReleaser.class);

    /** {@code admin_alerts.type} ≤ 60 : préfixe (20) + UUID (36). */
    static final String REFUND_ALERT_PREFIX = "LATE_CAPTURE_REFUND_";

    static final String AUDIT_RELEASED = "LATE_AUTHORIZATION_RELEASED";
    static final String AUDIT_REFUNDED = "LATE_CAPTURE_REFUNDED";
    static final String AUDIT_REALIGNED = "LATE_CAPTURE_ALREADY_REFUNDED";

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

    /**
     * @param deadStatus statut du paiement relu en base : {@code CANCELLED} ou {@code REFUNDED}
     * @return vrai si une action a été menée (libération, remboursement ou réalignement)
     */
    public boolean release(UUID paymentId, String paymentIntentId, UUID bidId, PaymentStatus deadStatus) {
        PaymentIntent pi;
        try {
            pi = stripeGateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            log.error("Autorisation tardive du paiement {} : lecture du PI {} impossible ({})",
                    paymentId, paymentIntentId, e.getMessage());
            return false;
        }
        String status = pi.getStatus();
        Map<String, Object> payload = new HashMap<>();
        payload.put("piId", paymentIntentId);
        payload.put("bidId", String.valueOf(bidId));
        payload.put("stripeStatus", status);
        payload.put("paymentStatus", String.valueOf(deadStatus));

        if ("requires_capture".equals(status)) {
            try {
                pi.cancel(PaymentIntentCancelParams.builder()
                        .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                        .build());
            } catch (StripeException e) {
                log.error("Autorisation tardive du paiement {} : libération du PI {} impossible ({})",
                        paymentId, paymentIntentId, e.getMessage());
                return false;
            }
            auditService.log("PAYMENT", paymentId, AUDIT_RELEASED, null, payload);
            log.warn("Autorisation tardive libérée : paiement {} déjà {}, PI {} annulé",
                    paymentId, deadStatus, paymentIntentId);
            return true;
        }
        if ("succeeded".equals(status) && deadStatus == PaymentStatus.CANCELLED) {
            return refundCapture(paymentId, paymentIntentId, pi, payload);
        }
        log.info("Paiement {} {} , PI {} en {} : rien à faire", paymentId, deadStatus, paymentIntentId, status);
        return false;
    }

    private boolean refundCapture(UUID paymentId, String paymentIntentId, PaymentIntent pi,
                                  Map<String, Object> payload) {
        if (alreadyFullyRefunded(pi)) {
            paymentRepository.markRefundedIfCancelled(paymentId);
            auditService.log("PAYMENT", paymentId, AUDIT_REALIGNED, null, payload);
            log.info("Capture tardive du paiement {} déjà remboursée chez Stripe : réalignement", paymentId);
            return true;
        }
        payload.put("alert", REFUND_ALERT_PREFIX + paymentId);
        try {
            Refund.create(RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                    RequestOptions.builder().setIdempotencyKey("refund-" + paymentId).build());
        } catch (StripeException e) {
            log.error("Capture tardive du paiement {} : remboursement du PI {} impossible ({})",
                    paymentId, paymentIntentId, e.getMessage());
            alerts.raiseOnce(REFUND_ALERT_PREFIX + paymentId,
                    "Paiement " + paymentId + " annulé mais capturé chez Stripe (PI " + paymentIntentId
                            + ") : remboursement automatique en échec, à rembourser à la main",
                    payload);
            return false;
        }
        paymentRepository.markRefundedIfCancelled(paymentId);
        auditService.log("PAYMENT", paymentId, AUDIT_REFUNDED, null, payload);
        alerts.raiseOnce(REFUND_ALERT_PREFIX + paymentId,
                "Paiement " + paymentId + " annulé mais capturé chez Stripe (PI " + paymentIntentId
                        + ") : remboursement intégral émis, à vérifier",
                payload);
        return true;
    }

    /**
     * Charge déjà intégralement remboursée ({@code amount_refunded >= amount}) ? Lue sur la charge
     * développée du PaymentIntent, sinon relue par son identifiant. Illisible : on considère qu'elle
     * ne l'est pas (le Refund idempotent {@code refund-<paymentId>} ne rembourse jamais deux fois).
     */
    private boolean alreadyFullyRefunded(PaymentIntent pi) {
        try {
            Charge charge = pi.getLatestChargeObject();
            if (charge == null && pi.getLatestCharge() != null) {
                charge = Charge.retrieve(pi.getLatestCharge());
            }
            if (charge == null) {
                return false;
            }
            if (Boolean.TRUE.equals(charge.getRefunded())) {
                return true;
            }
            Long amount = charge.getAmount();
            Long refunded = charge.getAmountRefunded();
            return amount != null && refunded != null && refunded >= amount;
        } catch (StripeException | RuntimeException e) {
            log.warn("Charge du PI {} illisible ({}) : remboursement idempotent tenté", pi.getId(), e.getMessage());
            return false;
        }
    }
}

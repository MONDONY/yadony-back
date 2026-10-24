package com.yadony.api.payments.amendment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Branche des webhooks Stripe des modifications de colis, appelée EN TÊTE de
 * {@code PaymentStripeWebhookHandler.handle}, juste après celle du supplément : un handler dédié ne pourrait
 * pas s'enregistrer sur {@code payment_intent.succeeded} sans avaler les recharges et les commissions.
 *
 * <ul>
 *   <li>{@code payment_intent.*} : routé par la métadonnée {@code commission_purpose = bid_amendment_commission}
 *       (commission du voyageur, rail espèces) ou {@code bid_amendment_id} (paiement de l'expéditeur, rail
 *       carte : traité par B9, avalé ici en attendant pour que le colis ne le prenne jamais pour le sien) ;</li>
 *   <li>{@code charge.refunded}, {@code charge.refund.updated} et {@code charge.dispute.*} : routés par la
 *       PaymentIntent de commission enregistrée, ou à défaut par la métadonnée {@code bid_amendment_id} de la
 *       charge, du Refund ou de la PaymentIntent (relue chez Stripe pour un litige) : une PaymentIntent d'un essai
 *       précédent n'est plus enregistrée mais doit rester retrouvable.</li>
 * </ul>
 * Tout le reste passe la main. Le payload brut est lu (et non l'objet désérialisé) : un écart de version d'API
 * entre Stripe et le SDK rend {@code getObject()} vide sans changer le JSON. Un rejeu n'a aucun effet : les
 * transitions relisent le statut sous verrou.
 */
@Component
public class BidAmendmentStripeWebhookRouter {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentStripeWebhookRouter.class);

    private final BidAmendmentCashRail cashRail;
    private final BidAmendmentCashLedger cashLedger;
    private final BidAmendmentRepository amendments;
    private final AdminAlertEscalator alerts;
    private final StripeSupplementGateway gateway;
    private final ObjectMapper mapper;

    public BidAmendmentStripeWebhookRouter(BidAmendmentCashRail cashRail, BidAmendmentCashLedger cashLedger,
                                           BidAmendmentRepository amendments, AdminAlertEscalator alerts,
                                           StripeSupplementGateway gateway, ObjectMapper mapper) {
        this.cashRail = cashRail;
        this.cashLedger = cashLedger;
        this.amendments = amendments;
        this.alerts = alerts;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    /**
     * Traite l'événement s'il concerne une modification.
     *
     * @return vrai si l'événement est celui d'une modification (traité ici, à ne PAS passer aux autres
     *         traitements), faux sinon.
     */
    public boolean handle(Event event) {
        String type = event.getType();
        if (type == null || !(type.startsWith("payment_intent.") || "charge.refunded".equals(type)
                || "charge.refund.updated".equals(type) || type.startsWith("charge.dispute."))) {
            return false;
        }
        JsonNode object = object(event);
        if (object == null) {
            return false;
        }
        if (type.startsWith("payment_intent.")) {
            return handlePaymentIntent(type, object);
        }
        if ("charge.refunded".equals(type)) {
            return handleChargeRefunded(object);
        }
        if ("charge.refund.updated".equals(type)) {
            return handleRefundUpdated(object);
        }
        return handleDispute(type, object);
    }

    private boolean handlePaymentIntent(String type, JsonNode pi) {
        JsonNode metadata = pi.path("metadata");
        String purpose = metadata.path("commission_purpose").asText(null);
        boolean commission = BidAmendmentCashRail.COMMISSION_PURPOSE.equals(purpose);
        String rawId = metadata.path("bid_amendment_id").asText(null);
        if (!commission && (rawId == null || rawId.isBlank())) {
            return false;
        }
        UUID amendmentId;
        try {
            amendmentId = UUID.fromString(rawId == null ? "" : rawId);
        } catch (IllegalArgumentException e) {
            // Métadonnée de modification illisible : jamais transmise aux traitements du colis.
            log.warn("Webhook {} : bid_amendment_id illisible ({})", type, rawId);
            return true;
        }
        if (commission) {
            // Commission d'une modification espèces, prélevée au voyageur : jamais un paiement de l'expéditeur.
            cashRail.onCommissionWebhook(type, amendmentId, pi.path("id").asText(null),
                    metadata.path("commission_attempt").asText(null),
                    pi.path("last_payment_error").path("code").asText("unknown"));
            return true;
        }
        // Paiement carte de l'expéditeur : routé en B9. Avalé d'ici là.
        log.debug("Webhook {} de la modification {} : rail carte non traité ici", type, amendmentId);
        return true;
    }

    /**
     * Commission d'une modification espèces remboursée (par nous, ou depuis le Dashboard) : pour la PaymentIntent
     * courante, {@code CHARGED → REFUNDED} si le remboursement est intégral, sinon alerte ; intégral sur une
     * modification encore vivante (proposée, acceptée) : alerte aussi. Pour la PaymentIntent d'un essai précédent
     * (remboursée par {@code refundStale}), le remboursement n'est que tracé : la commission de la modification
     * n'est pas la sienne.
     */
    private boolean handleChargeRefunded(JsonNode charge) {
        String piId = charge.path("payment_intent").asText(null);
        Optional<BidAmendmentEntity> found = resolve(charge, piId, false);
        if (found.isEmpty()) {
            return false;
        }
        BidAmendmentEntity a = found.get();
        boolean current = isCurrent(a, piId);
        long amount = charge.path("amount").asLong(0);
        long refunded = charge.path("amount_refunded").asLong(0);
        if (amount > 0 && refunded >= amount) {
            JsonNode refunds = charge.path("refunds").path("data");
            String refundId = refunds.isArray() && !refunds.isEmpty() ? refunds.get(0).path("id").asText(null) : null;
            if (!current) {
                cashLedger.auditOnly(a.getId(), "BID_AMENDMENT_STALE_COMMISSION_REFUND_CONFIRMED",
                        Map.of("piId", String.valueOf(piId), "refundId", String.valueOf(refundId)));
            } else if (cashLedger.cardRefunded(a.getId(), refundId, "webhook", false)
                    && (a.getStatus().isOpen() || a.getStatus() == BidAmendmentStatus.ACCEPTED)) {
                // Remboursée hors de l'app (Dashboard) alors que la modification vit encore (ouverte, acceptée) :
                // la commission n'est plus payée, un admin décide.
                alerts.raiseOnce("AMEND_COMM_REFUND_LIVE_" + a.getId(),
                        "Commission de la modification " + a.getId() + " remboursée alors qu'elle est "
                                + a.getStatus() + " : à vérifier",
                        Map.of("amendmentId", a.getId().toString(), "bidId", a.getBidId().toString(),
                                "status", a.getStatus().name()));
            }
        } else {
            cashLedger.auditOnly(a.getId(), "BID_AMENDMENT_COMMISSION_PARTIAL_REFUND",
                    Map.of("amountRefunded", refunded, "amount", amount, "piId", String.valueOf(piId)));
            alerts.raiseOnce("AMEND_COMM_PARTIAL_REF_" + a.getId(),
                    "Remboursement partiel de la commission de la modification " + a.getId()
                            + " : à traiter à la main",
                    Map.of("amendmentId", a.getId().toString(), "amountRefunded", refunded, "amount", amount));
        }
        return true;
    }

    /**
     * {@code charge.refund.updated} : un Refund de commission qui échoue après coup (carte expirée, compte
     * fermé) n'a pas rendu l'argent. Pour la PaymentIntent courante, la commission passe à {@code REFUND_FAILED} ;
     * pour celle d'un essai précédent, seule l'alerte part. Les autres mises à jour sont avalées sans effet.
     */
    private boolean handleRefundUpdated(JsonNode refund) {
        String piId = refund.path("payment_intent").asText(null);
        Optional<BidAmendmentEntity> found = resolve(refund, piId, false);
        if (found.isEmpty()) {
            return false;
        }
        BidAmendmentEntity a = found.get();
        String status = refund.path("status").asText("");
        String refundId = refund.path("id").asText(null);
        if (!"failed".equals(status) && !"canceled".equals(status)) {
            return true;
        }
        String reason = refund.path("failure_reason").asText("inconnu");
        if (!isCurrent(a, piId)) {
            alerts.raiseOnce("AMEND_STALE_RFD_FAIL_" + refundId,
                    "Remboursement " + refundId + " d'une commission hors essai de la modification " + a.getId()
                            + " échoué chez Stripe (" + reason + ") : commission à rendre au voyageur à la main",
                    Map.of("amendmentId", a.getId().toString(), "bidId", a.getBidId().toString(),
                            "refundId", String.valueOf(refundId), "paymentIntentId", String.valueOf(piId)));
        } else if (cashLedger.commissionRefundFailed(a.getId(), refundId)) {
            alerts.raiseOnce("AMEND_COMM_REFUND_FAIL_" + a.getId(),
                    "Remboursement " + refundId + " de la commission de la modification " + a.getId()
                            + " échoué chez Stripe (" + reason + ") : commission à rendre au voyageur à la main",
                    Map.of("amendmentId", a.getId().toString(), "bidId", a.getBidId().toString(),
                            "refundId", String.valueOf(refundId)));
        }
        return true;
    }

    /**
     * Litige bancaire sur la charge d'une commission (PaymentIntent courante ou d'un essai précédent) : alerte
     * admin, jamais {@code ChargebackService} (qui créerait un chargeback sans paiement). Pas de retenue
     * automatique en v1. Un litige ne porte pas la métadonnée de la PaymentIntent : elle est relue chez Stripe
     * quand la base ne la connaît pas.
     */
    private boolean handleDispute(String type, JsonNode dispute) {
        String piId = dispute.path("payment_intent").asText(null);
        Optional<BidAmendmentEntity> found = resolve(dispute, piId, true);
        if (found.isEmpty()) {
            return false;
        }
        BidAmendmentEntity a = found.get();
        cashLedger.auditOnly(a.getId(), "BID_AMENDMENT_CHARGEBACK",
                Map.of("event", type, "disputeId", dispute.path("id").asText("unknown"),
                        "disputeStatus", dispute.path("status").asText("unknown")));
        if ("charge.dispute.created".equals(type)) {
            alerts.raiseOnce("AMEND_CHARGEBACK_" + a.getId(),
                    "Litige bancaire ouvert sur la commission de la modification " + a.getId() + " (statut "
                            + a.getStatus() + ")",
                    Map.of("amendmentId", a.getId().toString(), "bidId", a.getBidId().toString(),
                            "disputeId", dispute.path("id").asText("unknown")));
        }
        return true;
    }

    /** Même prédicat que le ledger : la PaymentIntent d'un essai échoué, encore enregistrée, n'est plus la courante. */
    private static boolean isCurrent(BidAmendmentEntity a, String paymentIntentId) {
        return BidAmendmentCashLedger.isCurrent(a, paymentIntentId, null);
    }

    /**
     * Modification d'une charge, d'un Refund ou d'un litige de commission. D'abord la PaymentIntent enregistrée ;
     * sinon la métadonnée {@code bid_amendment_id} (une charge hérite de celle de sa PaymentIntent, un Refund porte
     * celle posée à sa création), pour retrouver aussi la PaymentIntent d'un essai précédent ; pour un litige,
     * qui ne porte rien, la métadonnée de la PaymentIntent relue chez Stripe.
     */
    private Optional<BidAmendmentEntity> resolve(JsonNode object, String paymentIntentId, boolean readFromStripe) {
        if (paymentIntentId != null && !paymentIntentId.isBlank()) {
            Optional<BidAmendmentEntity> registered = amendments.findByCommissionPaymentIntentId(paymentIntentId);
            if (registered.isPresent()) {
                return registered;
            }
        }
        Optional<BidAmendmentEntity> fromMetadata = fromMetadata(
                object.path("metadata").path("commission_purpose").asText(null),
                object.path("metadata").path("bid_amendment_id").asText(null));
        if (fromMetadata.isPresent() || !readFromStripe || paymentIntentId == null || paymentIntentId.isBlank()) {
            return fromMetadata;
        }
        try {
            Map<String, String> metadata = gateway.retrieveMetadata(paymentIntentId);
            return fromMetadata(metadata.get("commission_purpose"), metadata.get("bid_amendment_id"));
        } catch (Exception e) {
            log.warn("Événement sur la PaymentIntent {} : métadonnée illisible ({})", paymentIntentId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BidAmendmentEntity> fromMetadata(String purpose, String rawAmendmentId) {
        if (!BidAmendmentCashRail.COMMISSION_PURPOSE.equals(purpose) || rawAmendmentId == null) {
            return Optional.empty();
        }
        try {
            return amendments.findById(UUID.fromString(rawAmendmentId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private JsonNode object(Event event) {
        try {
            String raw = event.getDataObjectDeserializer().getRawJson();
            return raw == null ? null : mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Webhook {} : objet illisible ({})", event.getId(), e.getMessage());
            return null;
        }
    }
}

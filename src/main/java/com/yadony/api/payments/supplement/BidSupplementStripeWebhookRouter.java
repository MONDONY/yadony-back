package com.yadony.api.payments.supplement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.yadony.api.admin.AdminAlertEscalator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Branche des webhooks Stripe du supplément, appelée EN TÊTE de
 * {@code PaymentStripeWebhookHandler.handle} (plan §1.3-d) : un handler dédié ne pourrait pas
 * s'enregistrer sur {@code payment_intent.succeeded} sans avaler les recharges et les commissions.
 *
 * <ul>
 *   <li>{@code payment_intent.*} : routé par la métadonnée {@code bid_supplement_id} ;</li>
 *   <li>{@code charge.refunded} et {@code charge.dispute.*} : routés par une recherche en base sur
 *       le PaymentIntent ou la charge (la métadonnée d'une charge ne fait pas foi).</li>
 * </ul>
 *
 * Le payload brut est lu (et non l'objet désérialisé) : un écart de version d'API entre Stripe et
 * le SDK rend {@code getObject()} vide sans changer le JSON. Un rejeu (même événement, ou
 * événements dans le désordre) n'a aucun effet : les transitions relisent le statut sous verrou.
 */
@Component
public class BidSupplementStripeWebhookRouter {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementStripeWebhookRouter.class);

    private final BidSupplementCardRail rail;
    private final BidSupplementCardLedger ledger;
    private final BidSupplementRepository supplements;
    private final AdminAlertEscalator alerts;
    private final ObjectMapper mapper;

    public BidSupplementStripeWebhookRouter(BidSupplementCardRail rail, BidSupplementCardLedger ledger,
                                            BidSupplementRepository supplements, AdminAlertEscalator alerts,
                                            ObjectMapper mapper) {
        this.rail = rail;
        this.ledger = ledger;
        this.supplements = supplements;
        this.alerts = alerts;
        this.mapper = mapper;
    }

    /**
     * Traite l'événement s'il concerne un supplément.
     *
     * @return vrai si l'événement est celui d'un supplément (traité ici, à ne PAS passer aux
     *         traitements du colis), faux sinon.
     */
    public boolean handle(Event event) {
        String type = event.getType();
        if (type == null || !(type.startsWith("payment_intent.") || "charge.refunded".equals(type)
                || type.startsWith("charge.dispute."))) {
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
        return handleDispute(type, object);
    }

    private boolean handlePaymentIntent(String type, JsonNode pi) {
        String rawId = pi.path("metadata").path("bid_supplement_id").asText(null);
        if (rawId == null || rawId.isBlank()) {
            return false;
        }
        UUID supplementId;
        try {
            supplementId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            // Métadonnée de supplément illisible : jamais transmise aux traitements du colis.
            log.warn("Webhook {} : bid_supplement_id illisible ({})", type, rawId);
            return true;
        }
        String piId = pi.path("id").asText(null);
        switch (type) {
            case "payment_intent.succeeded" -> rail.onPaymentSucceeded(supplementId, piId,
                    pi.path("latest_charge").isTextual() ? pi.path("latest_charge").asText() : null);
            case "payment_intent.payment_failed" -> ledger.auditOnly(supplementId, "BID_SUPPLEMENT_PAYMENT_FAILED",
                    Map.of("piId", String.valueOf(piId),
                            "code", pi.path("last_payment_error").path("code").asText("unknown")));
            case "payment_intent.canceled" -> ledger.auditOnly(supplementId, "BID_SUPPLEMENT_PI_CANCELED",
                    Map.of("piId", String.valueOf(piId)));
            default -> log.debug("Webhook {} du supplément {} : rien à faire", type, supplementId);
        }
        return true;
    }

    private boolean handleChargeRefunded(JsonNode charge) {
        Optional<BidSupplementEntity> found = lookup(charge.path("payment_intent").asText(null),
                charge.path("id").asText(null));
        if (found.isEmpty()) {
            return false;
        }
        BidSupplementEntity s = found.get();
        long amount = charge.path("amount").asLong(0);
        long refunded = charge.path("amount_refunded").asLong(0);
        if (amount > 0 && refunded >= amount) {
            JsonNode refunds = charge.path("refunds").path("data");
            String refundId = refunds.isArray() && !refunds.isEmpty() ? refunds.get(0).path("id").asText(null) : null;
            if (!ledger.completeRefund(s.getId(), refundId, "webhook")
                    && (s.getStatus() == BidSupplementStatus.PAYOUT_PENDING
                    || s.getStatus() == BidSupplementStatus.PAID_OUT)) {
                alerts.raiseOnce("SUPP_REFUND_PAID_OUT_" + s.getId(),
                        "Charge du supplément " + s.getId() + " remboursée alors que le supplément est déjà versé",
                        Map.of("supplementId", s.getId().toString(), "bidId", s.getBidId().toString()));
            }
        } else {
            ledger.auditOnly(s.getId(), "BID_SUPPLEMENT_PARTIAL_REFUND",
                    Map.of("amountRefunded", refunded, "amount", amount));
            alerts.raiseOnce("SUPP_PARTIAL_REFUND_" + s.getId(),
                    "Remboursement partiel de la charge du supplément " + s.getId() + " : à traiter à la main",
                    Map.of("supplementId", s.getId().toString(), "amountRefunded", refunded, "amount", amount));
        }
        return true;
    }

    /**
     * Litige bancaire sur la charge d'un supplément : alerte admin, jamais {@code ChargebackService}
     * (qui créerait un chargeback sans paiement). Pas de retenue automatique en v1.
     */
    private boolean handleDispute(String type, JsonNode dispute) {
        Optional<BidSupplementEntity> found = lookup(dispute.path("payment_intent").asText(null),
                dispute.path("charge").asText(null));
        if (found.isEmpty()) {
            return false;
        }
        BidSupplementEntity s = found.get();
        ledger.auditOnly(s.getId(), "BID_SUPPLEMENT_CHARGEBACK",
                Map.of("event", type, "disputeId", dispute.path("id").asText("unknown"),
                        "disputeStatus", dispute.path("status").asText("unknown")));
        if ("charge.dispute.created".equals(type)) {
            alerts.raiseOnce("SUPP_CHARGEBACK_" + s.getId(),
                    "Litige bancaire ouvert sur la charge du supplément " + s.getId() + " (statut "
                            + s.getStatus() + ")",
                    Map.of("supplementId", s.getId().toString(), "bidId", s.getBidId().toString(),
                            "disputeId", dispute.path("id").asText("unknown")));
        }
        return true;
    }

    private Optional<BidSupplementEntity> lookup(String paymentIntentId, String chargeId) {
        Optional<BidSupplementEntity> found = paymentIntentId == null || paymentIntentId.isBlank()
                ? Optional.empty() : supplements.findByStripePaymentIntentId(paymentIntentId);
        if (found.isEmpty() && chargeId != null && !chargeId.isBlank()) {
            found = supplements.findByStripeChargeId(chargeId);
        }
        return found;
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

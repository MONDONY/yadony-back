package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Remboursement intégral d'un supplément carte à l'expéditeur (plan §4.2 « Remboursement ») :
 * colis terminé sans livraison, ou paiement arrivé après la clôture de la demande. Réservation en
 * transaction courte, Refund Stripe hors transaction (clé {@code supplement-refund-<id>}, reprise
 * par la métadonnée {@code bid_supplement_id} au-delà de 24 h), puis {@code REFUNDED}. Échec : le
 * supplément reste {@code REFUND_PENDING} (ou payé en retard), alerte, le filet réessaie.
 */
@Component
public class BidSupplementCardRefunds {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCardRefunds.class);

    private final StripeSupplementGateway gateway;
    private final BidSupplementCardLedger ledger;
    private final AdminAlertEscalator alerts;

    public BidSupplementCardRefunds(StripeSupplementGateway gateway, BidSupplementCardLedger ledger,
                                    AdminAlertEscalator alerts) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.alerts = alerts;
    }

    /** @return vrai si le supplément a été remboursé par cet appel. */
    public boolean refund(UUID supplementId) {
        BidSupplementCardLedger.RefundClaim decision = ledger.beginRefund(supplementId);
        switch (decision.decision()) {
            case NOTHING -> {
                return false;
            }
            case AFTER_PAYOUT -> {
                // Le Transfer est parti : pas d'inversion automatique en v1, un admin tranche.
                alerts.raiseOnce("SUPP_REFUND_PAID_OUT_" + supplementId,
                        "Supplément " + supplementId + " déjà versé au voyageur alors que le colis "
                                + decision.claim().bidId() + " n'est pas livré : remboursement manuel à décider",
                        Map.of("supplementId", supplementId.toString(), "bidId", decision.claim().bidId().toString()));
                return false;
            }
            default -> {
                return execute(decision.claim());
            }
        }
    }

    /**
     * Clé du Refund : {@code supplement-refund-<id>}, suffixée du refund échoué précédent s'il y en
     * a un (sinon, pendant 24 h, Stripe rendrait le même refund échoué au lieu d'en créer un).
     */
    static String refundKey(BidSupplementCardLedger.Claim claim) {
        String key = "supplement-refund-" + claim.supplementId();
        return claim.refundId() == null ? key : key + "-after-" + claim.refundId();
    }

    private boolean execute(BidSupplementCardLedger.Claim claim) {
        UUID id = claim.supplementId();
        try {
            if (claim.paymentIntentId() == null) {
                throw new IllegalStateException("PaymentIntent du supplément absent");
            }
            String refundId = gateway.findRefund(claim.paymentIntentId(), id.toString()).orElse(null);
            if (refundId == null) {
                refundId = gateway.createRefund(claim.paymentIntentId(),
                        Map.of("bid_supplement_id", id.toString(), "supplement_bid_id", claim.bidId().toString()),
                        refundKey(claim));
            }
            ledger.completeRefund(id, refundId, "refund");
            log.info("Supplément {} remboursé à l'expéditeur {} (Refund {})", id, claim.senderId(), refundId);
            return true;
        } catch (Exception e) {
            log.error("Remboursement du supplément {} en échec", id, e);
            ledger.auditOnly(id, "BID_SUPPLEMENT_REFUND_FAILED", Map.of("error", String.valueOf(e.getMessage())));
            alerts.raiseOnce("SUPP_REFUND_FAILED_" + id,
                    "Remboursement du supplément " + id + " en échec : " + e.getMessage(),
                    Map.of("supplementId", id.toString(), "bidId", claim.bidId().toString(),
                            "error", String.valueOf(e.getMessage())));
            return false;
        }
    }
}

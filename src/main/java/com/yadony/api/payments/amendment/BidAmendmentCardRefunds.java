package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Remboursement intégral d'une modification carte à l'expéditeur : modification acquise puis défaite
 * ({@code REFUND_PENDING} : non apportée, colis terminé sans livraison), ou montant capturé après la clôture de la
 * demande. Réservation en transaction courte, Refund Stripe hors transaction (clé {@code amendment-refund-<id>},
 * reprise par la métadonnée {@code bid_amendment_id} au-delà de 24 h), puis Refund enregistré ; la modification ne passe
 * {@code REFUNDED} qu'à la confirmation de Stripe ({@code charge.refunded}). Échec : la modification reste à rembourser,
 * alerte admin ; le filet des remboursements carte (B10) la reprend. Modification déjà versée au voyageur : alerte
 * {@code AMEND_REFUND_PAID_OUT_}, aucune action automatique (pas d'inversion de Transfer).
 */
@Component
public class BidAmendmentCardRefunds {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCardRefunds.class);

    /** Préfixe de l'alerte « remboursement demandé, déjà versée » ; avec un UUID, 58 caractères. */
    static final String PAID_OUT_ALERT_PREFIX = "AMEND_REFUND_PAID_OUT_";

    private final StripeAmendmentGateway gateway;
    private final BidAmendmentCardLedger ledger;
    private final AdminAlertEscalator alerts;

    public BidAmendmentCardRefunds(StripeAmendmentGateway gateway, BidAmendmentCardLedger ledger,
                                   AdminAlertEscalator alerts) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.alerts = alerts;
    }

    /** @return vrai si un Refund est désormais demandé chez Stripe (créé ou retrouvé). */
    public boolean refund(UUID amendmentId) {
        BidAmendmentCardLedger.RefundClaim claim = ledger.beginRefund(amendmentId);
        if (claim.decision() == BidAmendmentCardLedger.RefundDecision.AFTER_PAYOUT) {
            // Le Transfer est parti : pas d'inversion automatique, un admin tranche.
            alerts.raiseOnce(PAID_OUT_ALERT_PREFIX + amendmentId,
                    "Modification " + amendmentId + " déjà versée au voyageur alors que son remboursement est demandé "
                            + "(colis " + claim.bidId() + ") : remboursement manuel à décider",
                    Map.of("amendmentId", amendmentId.toString(), "bidId", String.valueOf(claim.bidId())));
            return false;
        }
        if (claim.decision() != BidAmendmentCardLedger.RefundDecision.PROCEED) {
            return false;
        }
        try {
            String refundId = gateway.findRefund(claim.paymentIntentId(), amendmentId.toString()).orElse(null);
            if (refundId == null) {
                refundId = gateway.createRefund(claim.paymentIntentId(),
                        Map.of("bid_amendment_id", amendmentId.toString(), "amendment_bid_id", claim.bidId().toString(),
                                "scope", BidAmendmentCardRail.SCOPE),
                        refundKey(claim));
            }
            if (refundId == null || refundId.isBlank()) {
                throw new IllegalStateException("Refund sans identifiant");
            }
            ledger.refundSubmitted(amendmentId, refundId);
            log.info("Modification {} : remboursement {} demandé à Stripe", amendmentId, refundId);
            return true;
        } catch (Exception e) {
            log.error("Remboursement de la modification {} en échec", amendmentId, e);
            ledger.auditOnly(amendmentId, "BID_AMENDMENT_REFUND_FAILED", Map.of("error", String.valueOf(e.getMessage())));
            alerts.raiseOnce("AMEND_CARD_REFUND_FAIL_" + amendmentId,
                    "Remboursement carte de la modification " + amendmentId + " en échec : " + e.getMessage(),
                    Map.of("amendmentId", amendmentId.toString(), "bidId", claim.bidId().toString(),
                            "error", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /**
     * Clé du Refund : {@code amendment-refund-<id>}, suffixée du Refund échoué précédent s'il y en a un (sinon, pendant
     * 24 h, Stripe rendrait le même Refund échoué au lieu d'en créer un).
     */
    static String refundKey(BidAmendmentCardLedger.RefundClaim claim) {
        String key = "amendment-refund-" + claim.amendmentId();
        return claim.previousRefundId() == null ? key : key + "-after-" + claim.previousRefundId();
    }
}

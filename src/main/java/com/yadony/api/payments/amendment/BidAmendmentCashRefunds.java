package com.yadony.api.payments.amendment;

import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Commission d'une modification espèces qui n'a pas abouti ou dont le colis s'arrête : toujours rendue au
 * voyageur. Appelé hors transaction (appel Stripe possible), par {@link BidAmendmentCashListener} après le commit
 * de la clôture, et par la fin de colis / « pas apportée » (B6) :
 * <ul>
 *   <li>commission prélevée sur le solde : recréditée ({@link BidAmendmentCashLedger#refundWallet}), en
 *       {@code REFUND} avec {@code payment_ref = "amendment-refund-<id>"} ;</li>
 *   <li>commission prélevée sur la carte : Refund Stripe intégral, clé {@code amendment_commission_refund_<piId>}
 *       (un Refund déjà créé est retrouvé) ;</li>
 *   <li>PaymentIntent de commission encore en 3DS : annulée, pour qu'elle ne puisse plus aboutir ; si elle a
 *       abouti entre-temps, la commission est enregistrée puis rendue ;</li>
 *   <li>modification acceptée sans rien à rendre (commission nulle) : simplement passée à {@code REFUNDED}.</li>
 * </ul>
 * Une modification acceptée dont la commission est rendue passe à {@code REFUNDED} ({@code refundedAt} posé,
 * {@code BidAmendmentRefundedEvent} avec {@code commissionOnly = true}). Un échec laisse la commission
 * {@code CHARGED} : le filet réessaie avec la même clé, une alerte admin part une seule fois.
 */
@Component
public class BidAmendmentCashRefunds {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCashRefunds.class);

    private final BidAmendmentCashLedger ledger;
    private final StripeSupplementGateway gateway;
    private final AdminAlertEscalator alerts;

    public BidAmendmentCashRefunds(BidAmendmentCashLedger ledger, StripeSupplementGateway gateway,
                                   AdminAlertEscalator alerts) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.alerts = alerts;
    }

    /**
     * Défait ce qui reste de la commission d'une modification terminée. Idempotent, et sans effet sur une
     * modification encore ouverte ({@code PROPOSED}, {@code COMMISSION_PENDING}…). À n'appeler QUE quand la
     * modification n'a plus d'avenir : refusée, échue, annulée, ou acceptée dont le colis s'arrête (fin de colis,
     * « pas apportée ») : une modification {@code ACCEPTED} passe alors à {@code REFUNDED}.
     *
     * @return vrai si la commission a été rendue, la PaymentIntent annulée ou la modification close par cet appel.
     */
    public boolean settle(UUID amendmentId) {
        BidAmendmentEntity a = ledger.find(amendmentId).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.CASH
                || !BidAmendmentCashLedger.SETTLEABLE.contains(a.getStatus())) {
            return false;
        }
        CommissionStatus commission = a.getCommissionStatus();
        boolean done = false;
        if (commission == CommissionStatus.CHARGED) {
            done = a.getCommissionChargedVia() == CommissionChargedVia.WALLET
                    ? ledger.refundWallet(amendmentId) : refundCard(amendmentId);
        } else if ((commission == CommissionStatus.REQUIRES_3DS || commission == null)
                && a.getCommissionPaymentIntentId() != null) {
            return abandon(amendmentId, a.getCommissionPaymentIntentId());
        }
        // Acceptée sans plus rien à rendre (commission nulle, ou déjà rendue chez Stripe avant nous) : elle n'est
        // plus acquise pour autant.
        return done || (a.getStatus() == BidAmendmentStatus.ACCEPTED && ledger.closeAccepted(amendmentId));
    }

    /** Annule la PaymentIntent de commission restée en 3DS ; si elle a abouti, la commission est rendue. */
    private boolean abandon(UUID amendmentId, String paymentIntentId) {
        try {
            gateway.cancelPaymentIntent(paymentIntentId);
            return ledger.commissionFailed(amendmentId, paymentIntentId, null, "abandoned");
        } catch (StripeException cancelRefused) {
            // Annulation refusée : le plus souvent parce que la 3DS vient d'aboutir.
            try {
                PaymentIntentView pi = gateway.retrievePaymentIntent(paymentIntentId);
                if (pi.succeeded()) {
                    // Commission tardive sur une modification close : enregistrée ; son remboursement part de
                    // l'événement publié par le registre (un seul chemin de remboursement).
                    return ledger.commissionCharged(amendmentId, pi.id(), null, "cancel-refused")
                            == BidAmendmentCashLedger.ChargeOutcome.LATE_REFUND;
                }
                if (pi.canceled()) {
                    return ledger.commissionFailed(amendmentId, paymentIntentId, null, "canceled");
                }
                log.warn("Commission {} de la modification {} non annulée (statut {}) : le filet réessaiera",
                        paymentIntentId, amendmentId, pi.status());
            } catch (StripeException retrieveError) {
                log.error("Commission {} de la modification {} : annulation et relecture en échec", paymentIntentId,
                        amendmentId, retrieveError);
            }
            return false;
        }
    }

    private boolean refundCard(UUID amendmentId) {
        BidAmendmentCashLedger.CardRefundClaim claim = ledger.cardRefundClaim(amendmentId).orElse(null);
        if (claim == null) {
            return false;
        }
        try {
            String refundId = gateway.findRefund(claim.paymentIntentId(), "bid_amendment_id",
                    amendmentId.toString()).orElse(null);
            if (refundId == null) {
                refundId = gateway.createRefund(claim.paymentIntentId(),
                        Map.of("bid_amendment_id", amendmentId.toString(),
                                "commission_purpose", BidAmendmentCashRail.COMMISSION_PURPOSE,
                                "amendment_bid_id", claim.bidId().toString()),
                        refundKey(claim.paymentIntentId()));
            }
            boolean done = ledger.cardRefunded(amendmentId, refundId, "refund", true);
            log.info("Commission de la modification {} rendue au voyageur {} (Refund {})", amendmentId,
                    claim.travelerId(), refundId);
            return done;
        } catch (Exception e) {
            boolean stillCharged = ledger.find(amendmentId)
                    .map(a -> a.getCommissionStatus() == CommissionStatus.CHARGED).orElse(false);
            if (!stillCharged) {
                // Un remboursement concurrent (événement et filet, webhook charge.refunded) a abouti.
                log.info("Commission de la modification {} déjà soldée par un autre passage ({})", amendmentId,
                        e.getMessage());
                return false;
            }
            if (e instanceof IdempotencyException) {
                // Même clé encore en vol chez Stripe (deux passages simultanés) : on réessaiera plus tard.
                log.info("Remboursement de la commission de la modification {} en cours ailleurs : réessai au filet",
                        amendmentId);
                return false;
            }
            log.error("Remboursement de la commission de la modification {} en échec", amendmentId, e);
            boolean raised = alerts.raiseOnce("AMEND_COMM_REFUND_FAIL_" + amendmentId,
                    "Commission de la modification espèces " + amendmentId + " non rendue : " + e.getMessage(),
                    Map.of("amendmentId", amendmentId.toString(), "bidId", claim.bidId().toString(),
                            "error", String.valueOf(e.getMessage())));
            if (raised) {
                // Une trace par incident, pas une à chaque passage du filet.
                ledger.auditOnly(amendmentId, "BID_AMENDMENT_COMMISSION_REFUND_ERROR",
                        Map.of("error", String.valueOf(e.getMessage())));
            }
            return false;
        }
    }

    /** Clé d'idempotence d'un Refund de commission : une par PaymentIntent (plusieurs peuvent exister par modification). */
    static String refundKey(String paymentIntentId) {
        return "amendment_commission_refund_" + paymentIntentId;
    }

    /**
     * Rend une PaymentIntent de commission aboutie qui n'a pu accepter aucune modification (essai précédent,
     * commission déjà payée autrement, modification qui n'en attend plus) : Refund intégral de CETTE PaymentIntent
     * (clé propre, un Refund déjà créé est retrouvé). L'alerte « commission hors essai » est déjà levée par le
     * ledger au moment où il la constate (elle survit à la perte de l'événement qui mène ici) ; un échec du Refund
     * lève une seconde alerte. Le rejeu des Refunds perdus ou échoués est celui du filet planifié (B6), à partir
     * de l'audit {@code BID_AMENDMENT_STALE_COMMISSION}.
     *
     * @return vrai si un Refund existe désormais (créé ici ou retrouvé).
     */
    public boolean refundStale(UUID amendmentId, UUID bidId, String paymentIntentId) {
        try {
            String refundId = gateway.findRefund(paymentIntentId, "bid_amendment_id", amendmentId.toString())
                    .orElse(null);
            if (refundId == null) {
                refundId = gateway.createRefund(paymentIntentId,
                        Map.of("bid_amendment_id", amendmentId.toString(),
                                "commission_purpose", BidAmendmentCashRail.COMMISSION_PURPOSE,
                                "amendment_bid_id", bidId.toString()),
                        refundKey(paymentIntentId));
            }
            ledger.auditOnly(amendmentId, "BID_AMENDMENT_STALE_COMMISSION_REFUNDED",
                    Map.of("piId", paymentIntentId, "refundId", refundId));
            return true;
        } catch (IdempotencyException e) {
            log.info("Remboursement de la PaymentIntent {} déjà en cours ailleurs", paymentIntentId);
            return false;
        } catch (Exception e) {
            log.error("Remboursement de la PaymentIntent de commission {} (modification {}) en échec",
                    paymentIntentId, amendmentId, e);
            // Compté par le filet : au-delà de 3 échecs, cette PaymentIntent n'est plus rejouée (l'alerte reste).
            ledger.auditOnly(amendmentId, "BID_AMENDMENT_STALE_COMMISSION_REFUND_FAILED",
                    Map.of("piId", paymentIntentId, "error", String.valueOf(e.getMessage())));
            alerts.raiseOnce("AMEND_STALE_REFUND_FAIL_" + paymentIntentId,
                    "Commission " + paymentIntentId + " de la modification " + amendmentId
                            + " prélevée hors de l'essai courant et NON rendue : " + e.getMessage(),
                    Map.of("amendmentId", amendmentId.toString(), "bidId", bidId.toString(),
                            "paymentIntentId", paymentIntentId, "error", String.valueOf(e.getMessage())));
            return false;
        }
    }
}

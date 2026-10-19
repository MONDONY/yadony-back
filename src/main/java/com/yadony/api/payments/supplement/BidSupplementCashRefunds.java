package com.yadony.api.payments.supplement;

import com.stripe.exception.StripeException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Commission d'un supplément espèces qui n'a pas abouti (décisions du propriétaire n° 1 et 6 :
 * toujours rendue au voyageur en cas de refus, d'échéance, de retrait ou de colis terminé sans
 * livraison). Appelé après le commit de la clôture et hors transaction (appel Stripe), par
 * {@link BidSupplementCashListener} et par le filet ({@link BidSupplementCashReconciliationJob}) :
 * <ul>
 *   <li>commission prélevée sur le solde : recréditée ({@link BidSupplementCashLedger#refundWallet}) ;</li>
 *   <li>commission prélevée sur la carte : Refund Stripe intégral, clé
 *       {@code supplement_commission_refund_<id>} (un Refund déjà créé est retrouvé) ;</li>
 *   <li>PaymentIntent de commission encore en 3DS : annulée, pour qu'elle ne puisse plus aboutir ;
 *       si elle a abouti entre-temps, la commission est enregistrée puis rendue.</li>
 * </ul>
 * Un échec laisse la commission {@code CHARGED} : le filet réessaie avec la même clé, une alerte
 * admin part une seule fois.
 */
@Component
public class BidSupplementCashRefunds {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCashRefunds.class);

    private final BidSupplementCashLedger ledger;
    private final StripeSupplementGateway gateway;
    private final AdminAlertEscalator alerts;

    public BidSupplementCashRefunds(BidSupplementCashLedger ledger, StripeSupplementGateway gateway,
                                    AdminAlertEscalator alerts) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.alerts = alerts;
    }

    /**
     * Défait ce qui reste de la commission d'une demande close. Idempotent.
     *
     * @return vrai si la commission a été rendue (ou la PaymentIntent annulée) par cet appel.
     */
    public boolean settle(UUID supplementId) {
        BidSupplementEntity s = ledger.find(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.CASH
                || !BidSupplementCashLedger.SETTLEABLE.contains(s.getStatus())) {
            return false;
        }
        CommissionStatus commission = s.getCommissionStatus();
        if (commission == CommissionStatus.CHARGED) {
            return s.getCommissionChargedVia() == CommissionChargedVia.WALLET
                    ? ledger.refundWallet(supplementId) : refundCard(supplementId);
        }
        if ((commission == CommissionStatus.REQUIRES_3DS || commission == null)
                && s.getCommissionPaymentIntentId() != null) {
            return abandon(supplementId, s.getCommissionPaymentIntentId());
        }
        return false;
    }

    /** Annule la PaymentIntent de commission restée en 3DS ; si elle a abouti, la commission est rendue. */
    private boolean abandon(UUID supplementId, String paymentIntentId) {
        try {
            gateway.cancelPaymentIntent(paymentIntentId);
            return ledger.commissionFailed(supplementId, paymentIntentId, "abandoned");
        } catch (StripeException cancelRefused) {
            // Annulation refusée : le plus souvent parce que la 3DS vient d'aboutir.
            try {
                PaymentIntentView pi = gateway.retrievePaymentIntent(paymentIntentId);
                if (pi.succeeded()) {
                    // Commission tardive : enregistrée, puis rendue tout de suite (et par l'événement).
                    ledger.commissionCharged(supplementId, pi.id(), "cancel-refused");
                    return refundCard(supplementId);
                }
                if (pi.canceled()) {
                    return ledger.commissionFailed(supplementId, paymentIntentId, "canceled");
                }
                log.warn("Commission {} du supplément {} non annulée (statut {}) : le filet réessaiera",
                        paymentIntentId, supplementId, pi.status());
            } catch (StripeException retrieveError) {
                log.error("Commission {} du supplément {} : annulation et relecture en échec", paymentIntentId,
                        supplementId, retrieveError);
            }
            return false;
        }
    }

    private boolean refundCard(UUID supplementId) {
        BidSupplementCashLedger.CardRefundClaim claim = ledger.cardRefundClaim(supplementId).orElse(null);
        if (claim == null) {
            return false;
        }
        try {
            String refundId = gateway.findRefund(claim.paymentIntentId(), supplementId.toString()).orElse(null);
            if (refundId == null) {
                refundId = gateway.createRefund(claim.paymentIntentId(),
                        Map.of("bid_supplement_id", supplementId.toString(),
                                "commission_purpose", BidSupplementCashRail.COMMISSION_PURPOSE,
                                "supplement_bid_id", claim.bidId().toString()),
                        refundKey(supplementId));
            }
            boolean done = ledger.cardRefunded(supplementId, refundId, "refund");
            log.info("Commission du supplément {} rendue au voyageur {} (Refund {})", supplementId,
                    claim.travelerId(), refundId);
            return done;
        } catch (Exception e) {
            log.error("Remboursement de la commission du supplément {} en échec", supplementId, e);
            ledger.auditOnly(supplementId, "BID_SUPPLEMENT_COMMISSION_REFUND_ERROR",
                    Map.of("error", String.valueOf(e.getMessage())));
            alerts.raiseOnce("SUPP_COMM_REFUND_FAIL_" + supplementId,
                    "Commission du supplément espèces " + supplementId + " non rendue : " + e.getMessage(),
                    Map.of("supplementId", supplementId.toString(), "bidId", claim.bidId().toString(),
                            "error", String.valueOf(e.getMessage())));
            return false;
        }
    }

    static String refundKey(UUID supplementId) {
        return "supplement_commission_refund_" + supplementId;
    }
}

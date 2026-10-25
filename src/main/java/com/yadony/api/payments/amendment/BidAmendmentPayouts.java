package com.yadony.api.payments.amendment;

import com.stripe.exception.StripeException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.StripeFailures;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway.ChargeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Versement au voyageur du net d'une modification carte acquise : Transfer Stripe Connect du compte plateforme vers le
 * compte du voyageur, adossé à la charge de la modification ({@code source_transaction}). Le groupe
 * {@code amendment_<id>} (jamais {@code payment_<id>}) et l'absence de métadonnée {@code payment_id} empêchent
 * {@code StripeTransferLookup} de prendre ce Transfer pour le versement principal du colis.
 *
 * <p>Déclenché par {@link BidAmendmentPayoutListener} (versement du principal, ou livraison d'un principal espèces) et
 * par le filet ({@link BidAmendmentReconciliationJob}). Réservation {@code ACCEPTED → PAYOUT_PENDING} dans une
 * transaction courte, appel Stripe hors transaction (clé {@code amendment-transfer-<id>}), puis {@code PAID_OUT}.
 * Refus définitif de Stripe (4xx) ou arrêt avant l'appel : retour à {@code ACCEPTED} et alerte ; issue inconnue
 * (coupure, délai, 5xx) ou Transfer parti mais enregistrement en échec : reste {@code PAYOUT_PENDING} (alerte), le
 * filet reprend. Une reprise cherche d'abord le Transfer du groupe : la clé
 * d'idempotence Stripe n'est valable que 24 h.
 *
 * <p>Jamais coupé par le drapeau {@code bid-amendment.enabled} : une modification déjà acquise est toujours versée.
 * Rail mobile money : tâche B11.
 */
@Component
public class BidAmendmentPayouts {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentPayouts.class);

    /** Préfixe de l'alerte « versement retenu » ; avec un UUID, 57 caractères. */
    static final String BLOCKED_ALERT_PREFIX = "AMEND_PAYOUT_BLOCKED_";

    private final StripeAmendmentGateway gateway;
    private final BidAmendmentCardLedger ledger;
    private final BidAmendmentRepository amendments;
    private final UserRepository users;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputes;
    private final AdminAlertEscalator alerts;

    public BidAmendmentPayouts(StripeAmendmentGateway gateway, BidAmendmentCardLedger ledger,
                               BidAmendmentRepository amendments, UserRepository users, PayoutHoldPolicy holdPolicy,
                               DisputeRepository disputes, AdminAlertEscalator alerts) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.amendments = amendments;
        this.users = users;
        this.holdPolicy = holdPolicy;
        this.disputes = disputes;
        this.alerts = alerts;
    }

    public static String transferGroup(UUID amendmentId) {
        return "amendment_" + amendmentId;
    }

    /** Principal versé (ou livré en espèces) : les modifications carte acquises de ce colis suivent. */
    public void payoutForBid(UUID bidId) {
        for (BidAmendmentEntity a : amendments.findByBidIdAndRailAndStatus(bidId, BidSupplementRail.STRIPE,
                BidAmendmentStatus.ACCEPTED)) {
            try {
                payout(a.getId());
            } catch (RuntimeException e) {
                log.error("Versement de la modification {} en échec", a.getId(), e);
            }
        }
    }

    /**
     * Verse une modification carte {@code ACCEPTED}. Retenue du voyageur, litige ouvert par l'administration, compte
     * Connect incomplet ou charge douteuse : elle reste {@code ACCEPTED}, alerte, le filet réessaie.
     *
     * @return vrai si la modification a été versée.
     */
    public boolean payout(UUID amendmentId) {
        BidAmendmentEntity a = amendments.findById(amendmentId).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.STRIPE || a.getStatus() != BidAmendmentStatus.ACCEPTED
                || a.getRefundRequestedAt() != null) {
            return false;
        }
        String blocked = blockingReason(a.getTravelerId(), a.getBidId(), a.getStripeChargeId(), a.getId());
        if (blocked != null) {
            raiseBlocked(a.getId(), a.getBidId(), blocked, "elle reste acceptée");
            return false;
        }
        return ledger.beginPayout(amendmentId).map(claim -> transfer(claim, false)).orElse(false);
    }

    /**
     * Reprise d'un versement resté {@code PAYOUT_PENDING} (filet). Sans Transfer retrouvé, les gardes sont réévaluées
     * avant tout envoi : une retenue ou un litige posé depuis la réservation l'emporte.
     */
    public boolean resume(UUID amendmentId) {
        return ledger.pendingPayout(amendmentId).map(claim -> transfer(claim, true)).orElse(false);
    }

    /**
     * Transfer de la claim. Seule une issue certaine ramène la modification à {@code ACCEPTED} (ou à
     * {@code REFUND_PENDING} si son remboursement a été demandé entre-temps, {@link BidAmendmentCardLedger#revertPayout}) :
     * arrêt avant l'appel, ou refus définitif de Stripe (4xx). Une issue inconnue (coupure, délai, 5xx, limite de débit,
     * recherche impossible) laisse {@code PAYOUT_PENDING} : le Transfer a pu partir, la modification ne doit pas
     * redevenir remboursable. Le filet reprend par le groupe, puis par la clé d'idempotence (même motif que
     * {@code StripeReleaseExecutor.onStripeFailure}).
     *
     * @param recheck reprise par le filet : gardes réévaluées si aucun Transfer n'existe.
     */
    private boolean transfer(BidAmendmentCardLedger.PayoutClaim claim, boolean recheck) {
        UUID id = claim.amendmentId();
        String transferId;
        try {
            transferId = gateway.findTransferByGroup(transferGroup(id)).orElse(null);
        } catch (Exception e) {
            return uncertain(claim, "recherche du Transfer impossible", e);
        }
        if (transferId == null) {
            String destination = claim.refundRequested() ? null : destination(claim.travelerId());
            String stop = stopBeforeCall(claim, recheck, destination);
            if (stop != null) {
                ledger.revertPayout(id, stop);
                return false;
            }
            try {
                SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(claim.currency());
                transferId = gateway.createTransfer(CurrencyAmount.of(claim.net(), currency).minor(), currency.code(),
                        destination, claim.chargeId(), transferGroup(id),
                        transferMetadata(id, claim.bidId()), "amendment-transfer-" + id);
            } catch (StripeException e) {
                if (!StripeFailures.definitiveRefusal(e)) {
                    return uncertain(claim, "issue du Transfer inconnue", e);
                }
                log.error("Transfer de la modification {} refusé par Stripe", id, e);
                ledger.revertPayout(id, e.getMessage());
                raiseFailed(claim, e.getMessage());
                return false;
            } catch (RuntimeException e) {
                return uncertain(claim, "issue du Transfer inconnue", e);
            }
        }
        try {
            return ledger.completePayout(id, transferId, claim.chargeId());
        } catch (RuntimeException e) {
            // Le Transfer est parti : surtout pas de retour à ACCEPTED (il repartirait). La modification reste
            // PAYOUT_PENDING ; le filet retrouve le Transfer par son groupe et enregistre.
            log.error("Transfer {} de la modification {} parti, enregistrement en échec", transferId, id, e);
            alerts.raiseOnce("AMEND_PAYOUT_UNRECORD_" + id,
                    "Transfer " + transferId + " de la modification " + id + " parti, mais versement non enregistré "
                            + "(" + e.getMessage() + ") : le filet le reprendra",
                    Map.of("amendmentId", id.toString(), "bidId", claim.bidId().toString(),
                            "transferId", transferId, "error", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /** Métadonnées du Transfer : jamais {@code payment_id}, que {@code StripeTransferLookup} lit pour le principal. */
    public static Map<String, String> transferMetadata(UUID amendmentId, UUID bidId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("bid_amendment_id", amendmentId.toString());
        metadata.put("amendment_bid_id", bidId.toString());
        metadata.put("source", "amendment-release");
        return metadata;
    }

    /** Raison de ne pas appeler Stripe (aucun Transfer n'existe), ou {@code null} ; alerte levée ici. */
    private String stopBeforeCall(BidAmendmentCardLedger.PayoutClaim claim, boolean recheck, String destination) {
        UUID id = claim.amendmentId();
        if (claim.refundRequested()) {
            log.info("Modification {} : remboursement demandé pendant le versement, aucun Transfer n'est parti", id);
            return BidAmendmentCardLedger.REFUND_REQUESTED_STOP;
        }
        if (recheck) {
            String blocked = blockingReason(claim.travelerId(), claim.bidId(), claim.chargeId(), id);
            if (blocked != null) {
                raiseBlocked(id, claim.bidId(), blocked, "elle redevient acceptée");
                return blocked;
            }
        }
        String missing = destination == null ? "compte Connect du voyageur absent ou incomplet"
                : claim.chargeId() == null ? "charge de la modification inconnue" : null;
        if (missing != null) {
            raiseFailed(claim, missing);
        }
        return missing;
    }

    /** Issue inconnue : la modification reste {@code PAYOUT_PENDING}, alerte, le filet reprend. */
    private boolean uncertain(BidAmendmentCardLedger.PayoutClaim claim, String what, Exception e) {
        UUID id = claim.amendmentId();
        log.error("Modification {} : {}, versement laissé en cours", id, what, e);
        alerts.raiseOnce("AMEND_TRANSFER_UNKNOWN_" + id,
                "Versement de la modification " + id + " : " + what + " (" + e.getMessage() + "). Il reste en cours, "
                        + "le filet le reprendra (Transfer cherché par son groupe, même clé d'idempotence)",
                Map.of("amendmentId", id.toString(), "bidId", claim.bidId().toString(),
                        "error", String.valueOf(e.getMessage())));
        return false;
    }

    private void raiseFailed(BidAmendmentCardLedger.PayoutClaim claim, String error) {
        UUID id = claim.amendmentId();
        alerts.raiseOnce("AMEND_TRANSFER_FAILED_" + id, "Transfer de la modification " + id + " en échec : " + error,
                Map.of("amendmentId", id.toString(), "bidId", claim.bidId().toString(),
                        "error", String.valueOf(error)));
    }

    private void raiseBlocked(UUID amendmentId, UUID bidId, String reason, String outcome) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("amendmentId", amendmentId.toString());
        context.put("bidId", bidId.toString());
        context.put("reason", reason);
        alerts.raiseOnce(BLOCKED_ALERT_PREFIX + amendmentId,
                "Versement de la modification " + amendmentId + " retenu (" + reason + ") : " + outcome
                        + ", le filet réessaiera", context);
    }

    /**
     * Raison de retenir le versement, ou {@code null}. Mêmes gardes que le versement principal (retenue du voyageur,
     * litige admin, compte Connect complet), plus l'état de la charge de la modification : une charge contestée ou
     * partiellement remboursée n'est pas versée (pas d'inversion de Transfer, un admin tranche).
     */
    private String blockingReason(UUID travelerId, UUID bidId, String chargeId, UUID amendmentId) {
        if (holdPolicy.isHeld(travelerId)) {
            return "PAYOUT_HOLD";
        }
        if (disputes.existsByBidIdAndStatusAndTypeStartingWith(bidId, DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)) {
            return "DISPUTE_OPEN";
        }
        if (destination(travelerId) == null) {
            return "STRIPE_ACCOUNT_NOT_READY";
        }
        if (chargeId == null) {
            return "CHARGE_UNKNOWN";
        }
        try {
            ChargeView charge = gateway.retrieveCharge(chargeId);
            if (charge.disputed()) {
                return "CHARGE_DISPUTED";
            }
            if (charge.amountRefunded() > 0) {
                return "CHARGE_PARTIALLY_REFUNDED";
            }
            return null;
        } catch (Exception e) {
            log.warn("Modification {} : état de la charge illisible ({})", amendmentId, e.getMessage());
            return "CHARGE_CHECK_FAILED";
        }
    }

    /** Compte Connect complet du voyageur ({@code ONBOARDING_COMPLETE}, comme le principal), sinon nul. */
    private String destination(UUID travelerId) {
        return users.findById(travelerId).filter(BidSupplementRail::stripeAccountReady)
                .map(UserEntity::getStripeAccountId).orElse(null);
    }
}

package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
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
 * Échec Stripe : retour à {@code ACCEPTED} et alerte ; Transfer parti mais enregistrement en échec : reste
 * {@code PAYOUT_PENDING} (alerte), le filet reprend. Une reprise cherche d'abord le Transfer du groupe : la clé
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
        if (a == null || a.getRail() != BidSupplementRail.STRIPE || a.getStatus() != BidAmendmentStatus.ACCEPTED) {
            return false;
        }
        String blocked = blockingReason(a);
        if (blocked != null) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("amendmentId", a.getId().toString());
            context.put("bidId", a.getBidId().toString());
            context.put("reason", blocked);
            alerts.raiseOnce(BLOCKED_ALERT_PREFIX + amendmentId,
                    "Versement de la modification " + amendmentId + " retenu (" + blocked + ") : elle reste "
                            + "acceptée, le filet réessaiera", context);
            return false;
        }
        return ledger.beginPayout(amendmentId).map(this::transfer).orElse(false);
    }

    /** Reprise d'un versement resté {@code PAYOUT_PENDING} (filet). */
    public boolean resume(UUID amendmentId) {
        return ledger.pendingPayout(amendmentId).map(this::transfer).orElse(false);
    }

    private boolean transfer(BidAmendmentCardLedger.PayoutClaim claim) {
        UUID id = claim.amendmentId();
        String transferId;
        try {
            transferId = gateway.findTransferByGroup(transferGroup(id)).orElse(null);
            if (transferId == null) {
                String destination = destination(claim.travelerId());
                if (destination == null) {
                    throw new IllegalStateException("compte Connect du voyageur absent ou incomplet");
                }
                if (claim.chargeId() == null) {
                    throw new IllegalStateException("charge de la modification inconnue");
                }
                SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(claim.currency());
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("bid_amendment_id", id.toString());
                metadata.put("amendment_bid_id", claim.bidId().toString());
                metadata.put("source", "amendment-release");
                transferId = gateway.createTransfer(CurrencyAmount.of(claim.net(), currency).minor(), currency.code(),
                        destination, claim.chargeId(), transferGroup(id), metadata, "amendment-transfer-" + id);
            }
        } catch (Exception e) {
            // Aucun Transfer : retour à ACCEPTED, le filet réessaie.
            log.error("Transfer de la modification {} en échec", id, e);
            ledger.revertPayout(id, e.getMessage());
            alerts.raiseOnce("AMEND_TRANSFER_FAILED_" + id,
                    "Transfer de la modification " + id + " en échec : " + e.getMessage(),
                    Map.of("amendmentId", id.toString(), "bidId", claim.bidId().toString(),
                            "error", String.valueOf(e.getMessage())));
            return false;
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

    /**
     * Raison de retenir le versement, ou {@code null}. Mêmes gardes que le versement principal (retenue du voyageur,
     * litige admin, compte Connect complet), plus l'état de la charge de la modification : une charge contestée ou
     * partiellement remboursée n'est pas versée (pas d'inversion de Transfer, un admin tranche).
     */
    private String blockingReason(BidAmendmentEntity a) {
        if (holdPolicy.isHeld(a.getTravelerId())) {
            return "PAYOUT_HOLD";
        }
        if (disputes.existsByBidIdAndStatusAndTypeStartingWith(a.getBidId(), DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)) {
            return "DISPUTE_OPEN";
        }
        if (destination(a.getTravelerId()) == null) {
            return "STRIPE_ACCOUNT_NOT_READY";
        }
        if (a.getStripeChargeId() == null) {
            return "CHARGE_UNKNOWN";
        }
        try {
            ChargeView charge = gateway.retrieveCharge(a.getStripeChargeId());
            if (charge.disputed()) {
                return "CHARGE_DISPUTED";
            }
            if (charge.amountRefunded() > 0) {
                return "CHARGE_PARTIALLY_REFUNDED";
            }
            return null;
        } catch (Exception e) {
            log.warn("Modification {} : état de la charge illisible ({})", a.getId(), e.getMessage());
            return "CHARGE_CHECK_FAILED";
        }
    }

    /** Compte Connect complet du voyageur ({@code ONBOARDING_COMPLETE}, comme le principal), sinon nul. */
    private String destination(UUID travelerId) {
        return users.findById(travelerId).filter(BidSupplementRail::stripeAccountReady)
                .map(UserEntity::getStripeAccountId).orElse(null);
    }
}

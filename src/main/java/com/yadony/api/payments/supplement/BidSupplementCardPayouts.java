package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Versement du net d'un supplément carte au voyageur (plan §4.2 « Versement ») : Transfer Stripe
 * Connect du compte plateforme vers le compte du voyageur, adossé à la charge du supplément
 * ({@code source_transaction}). Le groupe {@code supplement_<id>} (jamais {@code payment_<id>}) et
 * l'absence de métadonnée {@code payment_id} empêchent {@code StripeTransferLookup} de prendre ce
 * Transfer pour le versement principal du colis.
 *
 * <p>Réservation {@code PAID → PAYOUT_PENDING} dans une transaction courte, appel Stripe hors
 * transaction, puis {@code PAID_OUT} (ou retour à {@code PAID} et alerte). Une reprise cherche
 * d'abord le Transfer du groupe : la clé d'idempotence Stripe n'est valable que 24 h.
 */
@Component
public class BidSupplementCardPayouts {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCardPayouts.class);

    private final StripeSupplementGateway gateway;
    private final BidSupplementCardLedger ledger;
    private final BidSupplementRepository supplements;
    private final UserRepository users;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputes;
    private final AdminAlertEscalator alerts;

    public BidSupplementCardPayouts(StripeSupplementGateway gateway, BidSupplementCardLedger ledger,
                                    BidSupplementRepository supplements, UserRepository users,
                                    PayoutHoldPolicy holdPolicy, DisputeRepository disputes,
                                    AdminAlertEscalator alerts) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.supplements = supplements;
        this.users = users;
        this.holdPolicy = holdPolicy;
        this.disputes = disputes;
        this.alerts = alerts;
    }

    public static String transferGroup(UUID supplementId) {
        return "supplement_" + supplementId;
    }

    /** Versement du principal fait : le supplément carte payé de ce colis suit. */
    public void payoutForBid(UUID bidId) {
        for (BidSupplementEntity s : supplements.findByBidIdAndRailAndStatus(bidId, BidSupplementRail.STRIPE,
                BidSupplementStatus.PAID)) {
            payout(s.getId());
        }
    }

    /**
     * Verse un supplément {@code PAID}. Retenue du voyageur, litige ouvert par l'administration ou
     * compte Connect absent : le supplément reste {@code PAID}, alerte, le filet réessaie.
     *
     * @return vrai si le supplément a été versé.
     */
    public boolean payout(UUID supplementId) {
        BidSupplementEntity s = supplements.findById(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.STRIPE || s.getStatus() != BidSupplementStatus.PAID) {
            return false;
        }
        String blocked = blockingReason(s);
        if (blocked != null) {
            alerts.raiseOnce("SUPP_PAYOUT_BLOCKED_" + supplementId,
                    "Versement du supplément " + supplementId + " retenu (" + blocked + ") : il reste payé, "
                            + "le filet réessaiera",
                    context(s, Map.of("reason", blocked)));
            return false;
        }
        return ledger.beginPayout(supplementId).map(claim -> transfer(claim, destination(claim.travelerId())))
                .orElse(false);
    }

    /** Reprise d'un versement resté {@code PAYOUT_PENDING} (filet). */
    public boolean resume(UUID supplementId) {
        return ledger.pendingPayout(supplementId).map(claim -> transfer(claim, destination(claim.travelerId())))
                .orElse(false);
    }

    private boolean transfer(BidSupplementCardLedger.Claim claim, String destination) {
        UUID id = claim.supplementId();
        try {
            String transferId = gateway.findTransferByGroup(transferGroup(id)).orElse(null);
            String chargeId = claim.chargeId();
            if (transferId == null) {
                if (destination == null) {
                    throw new IllegalStateException("compte Connect du voyageur absent");
                }
                if (chargeId == null && claim.paymentIntentId() != null) {
                    chargeId = gateway.retrievePaymentIntent(claim.paymentIntentId()).latestCharge();
                }
                if (chargeId == null) {
                    throw new IllegalStateException("charge du supplément introuvable");
                }
                SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(claim.currency());
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("bid_supplement_id", id.toString());
                metadata.put("supplement_bid_id", claim.bidId().toString());
                metadata.put("source", "supplement-release");
                transferId = gateway.createTransfer(CurrencyAmount.of(claim.net(), currency).minor(), currency.code(),
                        destination, chargeId, transferGroup(id), metadata, "supplement-transfer-" + id);
            }
            ledger.completePayout(id, transferId, chargeId);
            log.info("Supplément {} versé au voyageur {} (Transfer {})", id, claim.travelerId(), transferId);
            return true;
        } catch (Exception e) {
            log.error("Versement du supplément {} en échec", id, e);
            ledger.revertPayout(id, e.getMessage());
            alerts.raiseOnce("SUPP_TRANSFER_FAILED_" + id,
                    "Transfer du supplément " + id + " en échec : " + e.getMessage(),
                    Map.of("supplementId", id.toString(), "bidId", claim.bidId().toString(),
                            "error", String.valueOf(e.getMessage())));
            return false;
        }
    }

    private String blockingReason(BidSupplementEntity s) {
        if (holdPolicy.isHeld(s.getTravelerId())) {
            return "PAYOUT_HOLD";
        }
        if (disputes.existsByBidIdAndStatusAndTypeStartingWith(s.getBidId(), DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)) {
            return "DISPUTE_OPEN";
        }
        if (destination(s.getTravelerId()) == null) {
            return "NO_STRIPE_ACCOUNT";
        }
        return null;
    }

    private String destination(UUID travelerId) {
        return users.findById(travelerId).map(UserEntity::getStripeAccountId)
                .filter(account -> !account.isBlank()).orElse(null);
    }

    private static Map<String, Object> context(BidSupplementEntity s, Map<String, Object> extra) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("supplementId", s.getId().toString());
        context.put("bidId", s.getBidId().toString());
        context.putAll(extra);
        return context;
    }
}

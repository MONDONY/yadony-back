package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyNetworks;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versement du net d'un supplément mobile money au voyageur (plan §4.3 « Versement »), après la
 * CONFIRMATION du versement principal du colis ({@code PaymentReleasedEvent} mobile money, publié
 * par {@code MobileMoneyPayoutOutcomeListener} sur le payout principal {@code COMPLETED}). Même
 * destination que le versement principal : le numéro, l'opérateur et le pays du payout principal
 * abouti ; à défaut, le compte de versement du voyageur, sur le réseau du dépôt du supplément.
 *
 * <p>Réservation {@code PAID → PAYOUT_PENDING} en transaction courte, payout pawaPay
 * ({@code BID_SUPPLEMENT}, voyageur en {@code user_id}) hors transaction, puis {@code PAID_OUT} à la
 * confirmation ({@link BidSupplementOutcomeListener}). Idempotent : au plus un payout vivant ou abouti
 * par supplément (index {@code uq_pawapay_ops_live_per_supplement}), une reprise relit l'opération
 * existante au lieu d'en soumettre une seconde. Aucun {@code PaymentReleasedEvent} n'est publié : le
 * supplément a son propre {@code BidSupplementPaidOutEvent}.
 */
@Component
public class BidSupplementMobileMoneyPayouts {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementMobileMoneyPayouts.class);

    private final BidSupplementMobileMoneyLedger ledger;
    private final BidSupplementRepository supplements;
    private final UserRepository users;
    private final PaymentRepository payments;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputes;
    private final AdminAlertEscalator alerts;

    public BidSupplementMobileMoneyPayouts(BidSupplementMobileMoneyLedger ledger, BidSupplementRepository supplements,
                                           UserRepository users, PaymentRepository payments,
                                           PawapayOperationService operations, PawapaySubmissionService submission,
                                           PayoutHoldPolicy holdPolicy, DisputeRepository disputes,
                                           AdminAlertEscalator alerts) {
        this.ledger = ledger;
        this.supplements = supplements;
        this.users = users;
        this.payments = payments;
        this.operations = operations;
        this.submission = submission;
        this.holdPolicy = holdPolicy;
        this.disputes = disputes;
        this.alerts = alerts;
    }

    /** Versement principal confirmé : le supplément mobile money payé de ce colis suit. */
    public void payoutForBid(java.util.UUID bidId) {
        for (BidSupplementEntity s : supplements.findByBidIdAndRailAndStatus(bidId, BidSupplementRail.PAWAPAY,
                BidSupplementStatus.PAID)) {
            payout(s.getId());
        }
    }

    /**
     * Verse un supplément {@code PAID}. Retenue du voyageur, litige ouvert par l'administration ou
     * compte de versement mobile money absent : il reste {@code PAID}, alerte, le filet réessaie.
     *
     * @return vrai si un payout a été soumis (ou retrouvé) pour ce supplément.
     */
    public boolean payout(UUID supplementId) {
        BidSupplementEntity s = supplements.findById(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY || s.getStatus() != BidSupplementStatus.PAID) {
            return false;
        }
        String blocked = blockingReason(s);
        if (blocked != null) {
            alerts.raiseOnce("SUPP_PAYOUT_BLOCKED_" + supplementId,
                    "Versement du supplément " + supplementId + " retenu (" + blocked + ") : il reste payé, "
                            + "le filet réessaiera",
                    context(supplementId, s.getBidId(), Map.of("reason", blocked)));
            return false;
        }
        return ledger.beginPayout(supplementId).map(this::submit).orElse(false);
    }

    /** Reprise d'un versement resté {@code PAYOUT_PENDING} (filet) : relit l'opération, sinon la soumet. */
    public boolean resume(UUID supplementId) {
        return ledger.pendingPayout(supplementId).map(this::submit).orElse(false);
    }

    private boolean submit(BidSupplementMobileMoneyLedger.Claim claim) {
        UUID id = claim.supplementId();
        Optional<PawapayOperationEntity> live = operations.findLiveForSupplement(id, PawapayOperationKind.PAYOUT);
        if (live.isPresent()) {
            return reuse(claim, live.get());
        }
        PawapayOperationEntity op;
        try {
            Destination destination = destination(claim);
            op = submission.submitSupplementPayout(id, claim.travelerId(), destination.msisdn(),
                    destination.provider(), destination.country(), claim.net(), claim.currency(),
                    BidSupplementMobileMoneyRail.clientReference(id));
        } catch (RuntimeException e) {
            // Opération créée puis réseau muet : elle reste au poller, le supplément PAYOUT_PENDING.
            Optional<PawapayOperationEntity> created = operations.findLiveForSupplement(id, PawapayOperationKind.PAYOUT);
            if (created.isPresent()) {
                log.warn("Payout du supplément {} sans réponse pawaPay, laissé au poller ({})", id, e.toString());
                return true;
            }
            log.error("Payout du supplément {} en échec", id, e);
            ledger.revertPayout(id, e.getMessage());
            alerts.raiseOnce("SUPP_MM_PAYOUT_FAILED_" + id,
                    "Versement mobile money du supplément " + id + " en échec : " + e.getMessage(),
                    context(id, claim.bidId(), Map.of("error", String.valueOf(e.getMessage()))));
            return false;
        }
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            ledger.revertPayout(id, op.getFailureCode());
            alerts.raiseOnce("SUPP_MM_PAYOUT_FAILED_" + id,
                    "pawaPay a refusé le versement du supplément " + id + " : " + op.getFailureCode(),
                    context(id, claim.bidId(), Map.of("operationId", op.getId().toString(),
                            "failureCode", String.valueOf(op.getFailureCode()))));
            return false;
        }
        ledger.auditOnly(id, "BID_SUPPLEMENT_PAYOUT_SUBMITTED", Map.of("operationId", op.getId().toString(),
                "provider", op.getProvider(), "msisdnMasked", op.getMsisdnMasked()));
        log.info("Payout mobile money {} soumis pour le supplément {}", op.getId(), id);
        return true;
    }

    /** Un payout existe déjà : abouti, on l'enregistre (événement perdu) ; en cours, on attend son issue. */
    private boolean reuse(BidSupplementMobileMoneyLedger.Claim claim, PawapayOperationEntity op) {
        if (op.getStatus() == PawapayOperationStatus.COMPLETED) {
            ledger.completePayout(claim.supplementId(), op.getId());
        }
        return true;
    }

    record Destination(String msisdn, String provider, String country) {
    }

    /**
     * Destination du versement principal abouti du colis (même numéro, même opérateur) ; à défaut,
     * le compte de versement du voyageur, sur le réseau du dépôt du supplément s'il l'accepte (règle
     * de {@code MobileMoneyPayoutInitiator}).
     */
    Destination destination(BidSupplementMobileMoneyLedger.Claim claim) {
        Optional<PawapayOperationEntity> principal = payments.findForBid(claim.bidId())
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY)
                .flatMap(p -> operations.findLive(p.getId(), PawapayOperationKind.PAYOUT))
                .filter(op -> op.getStatus() == PawapayOperationStatus.COMPLETED);
        if (principal.isPresent()) {
            PawapayOperationEntity op = principal.get();
            return new Destination(op.getMsisdn(), op.getProvider(), op.getCountry());
        }
        UserEntity traveler = users.findById(claim.travelerId())
                .orElseThrow(() -> new IllegalStateException("Voyageur introuvable : " + claim.travelerId()));
        String depositBrand = operations.findLiveForSupplement(claim.supplementId(), PawapayOperationKind.DEPOSIT)
                .map(PawapayOperationEntity::getProvider).map(PawapayProviders::brand).orElse(null);
        String provider = MobileMoneyNetworks.providerForBrand(traveler, depositBrand)
                .orElse(traveler.getMobileMoneyProvider());
        return new Destination(traveler.getMobileMoneyMsisdn(), provider, traveler.getMobileMoneyCountry());
    }

    /** Raison de retenir le versement, ou {@code null} : mêmes gardes que le versement principal. */
    private String blockingReason(BidSupplementEntity s) {
        if (holdPolicy.isHeld(s.getTravelerId())) {
            return "PAYOUT_HOLD";
        }
        if (disputes.existsByBidIdAndStatusAndTypeStartingWith(s.getBidId(), DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)) {
            return "DISPUTE_OPEN";
        }
        boolean account = users.findById(s.getTravelerId())
                .map(t -> t.canReceiveMobileMoney(s.getCurrency())).orElse(false);
        if (!account) {
            return "NO_MOBILE_MONEY_ACCOUNT";
        }
        return null;
    }

    private static Map<String, Object> context(UUID supplementId, UUID bidId, Map<String, Object> extra) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("supplementId", supplementId.toString());
        context.put("bidId", bidId.toString());
        context.putAll(extra);
        return context;
    }
}

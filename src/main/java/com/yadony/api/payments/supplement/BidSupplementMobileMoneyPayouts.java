package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccounts;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
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
 * par {@code MobileMoneyPayoutOutcomeListener} sur le payout principal {@code COMPLETED}). Destination :
 * le compte de versement du voyageur du même code pawaPay que le DÉPÔT DU SUPPLÉMENT (sinon le compte
 * repris par la migration), dans la devise du supplément ({@link MobileMoneyPayoutAccounts#destinationFor}),
 * c'est-à-dire le compte que le verrou {@code pendingPayouts} protège. Le numéro figé dans le payout
 * principal n'est jamais réutilisé tel quel : le voyageur a pu retirer ce compte ou en changer le numéro
 * une fois le principal abouti. Seul repli, sans dépôt lisible pour le supplément : le compte vivant qui
 * porte encore le code ET le numéro du payout principal.
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
    private final MobileMoneyPayoutAccounts accounts;
    private final PaymentRepository payments;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputes;
    private final AdminAlertEscalator alerts;
    private final PawapayClient client;
    private final BidSupplementProperties properties;

    public BidSupplementMobileMoneyPayouts(BidSupplementMobileMoneyLedger ledger, BidSupplementRepository supplements,
                                           UserRepository users, MobileMoneyPayoutAccounts accounts,
                                           PaymentRepository payments,
                                           PawapayOperationService operations, PawapaySubmissionService submission,
                                           PayoutHoldPolicy holdPolicy, DisputeRepository disputes,
                                           AdminAlertEscalator alerts, PawapayClient client,
                                           BidSupplementProperties properties) {
        this.ledger = ledger;
        this.supplements = supplements;
        this.users = users;
        this.accounts = accounts;
        this.payments = payments;
        this.operations = operations;
        this.submission = submission;
        this.holdPolicy = holdPolicy;
        this.disputes = disputes;
        this.alerts = alerts;
        this.client = client;
        this.properties = properties;
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
     * Montant hors des limites de versement de l'opérateur, ou trop de versements morts : il reste
     * {@code PAID} sans aucune soumission, alerte d'intervention manuelle.
     *
     * @return vrai si un payout a été soumis (ou retrouvé) pour ce supplément.
     */
    public boolean payout(UUID supplementId) {
        BidSupplementEntity s = supplements.findById(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY || s.getStatus() != BidSupplementStatus.PAID) {
            return false;
        }
        Optional<Destination> destination = clearedDestination(s);
        if (destination.isEmpty()) {
            return false;
        }
        return ledger.beginPayout(supplementId).map(claim -> submit(claim, destination.get())).orElse(false);
    }

    /**
     * Reprise d'un versement resté {@code PAYOUT_PENDING} (filet). Une opération vivante est relue,
     * jamais doublée ; sans opération, les gardes du versement sont rejouées (retenue, litige, compte,
     * plafond d'échecs, limites de l'opérateur) avant toute nouvelle soumission : si l'une bloque, retour
     * à {@code PAID}.
     */
    public boolean resume(UUID supplementId) {
        Optional<BidSupplementMobileMoneyLedger.Claim> pending = ledger.pendingPayout(supplementId);
        if (pending.isEmpty()) {
            return false;
        }
        BidSupplementMobileMoneyLedger.Claim claim = pending.get();
        Optional<PawapayOperationEntity> live = operations.findLiveForSupplement(supplementId,
                PawapayOperationKind.PAYOUT);
        if (live.isPresent()) {
            return reuse(claim, live.get());
        }
        BidSupplementEntity s = supplements.findById(supplementId).orElse(null);
        if (s == null) {
            return false;
        }
        String blocked = blockingReason(s);
        if (blocked != null) {
            ledger.revertPayout(supplementId, blocked);
            alertBlocked(s, blocked);
            return false;
        }
        Optional<Destination> destination = guardedDestination(s);
        if (destination.isEmpty()) {
            ledger.revertPayout(supplementId, "PAYOUT_GUARD");
            return false;
        }
        return submit(claim, destination.get());
    }

    /** Gardes du versement, puis destination ; vide (avec alerte) si un versement n'est pas permis. */
    private Optional<Destination> clearedDestination(BidSupplementEntity s) {
        String blocked = blockingReason(s);
        if (blocked != null) {
            alertBlocked(s, blocked);
            return Optional.empty();
        }
        return guardedDestination(s);
    }

    /**
     * Plafond d'échecs puis limites de versement de l'opérateur de destination. Ni l'un ni l'autre ne
     * se lève seul : aucune soumission automatique, alerte d'intervention manuelle.
     */
    private Optional<Destination> guardedDestination(BidSupplementEntity s) {
        UUID id = s.getId();
        long dead = operations.countDeadForSupplement(id, PawapayOperationKind.PAYOUT);
        if (dead >= properties.getMmMaxAttempts()) {
            alerts.raiseOnce("SUPP_MM_PAYOUT_MANUAL_" + id,
                    "Versement mobile money du supplément " + id + " : " + dead + " échecs, plus de nouvelle "
                            + "tentative automatique, intervention manuelle requise",
                    context(id, s.getBidId(), Map.of("deadOperations", dead)));
            return Optional.empty();
        }
        Optional<Destination> found = destination(s.getBidId(), id, s.getTravelerId(), s.getCurrency());
        if (found.isEmpty()) {
            // Aucun compte du code du dépôt ni compte repris dans cette devise : on ne verse jamais
            // sur un autre réseau, le supplément reste payé.
            alertBlocked(s, "NO_MOBILE_MONEY_ACCOUNT");
            return Optional.empty();
        }
        Destination destination = found.get();
        String outOfLimits = payoutLimitViolation(destination.provider(), s.getNetAmount());
        if (outOfLimits != null) {
            alerts.raiseOnce("SUPP_MM_PAYOUT_LIMITS_" + id,
                    "Versement mobile money du supplément " + id + " impossible (" + outOfLimits + ") : "
                            + "intervention manuelle requise",
                    context(id, s.getBidId(), Map.of("provider", String.valueOf(destination.provider()),
                            "net", s.getNetAmount().toPlainString(), "reason", outOfLimits)));
            return Optional.empty();
        }
        return Optional.of(destination);
    }

    /**
     * Raison pour laquelle l'opérateur refuserait ce versement (opérateur absent de la configuration
     * active, versement fermé, montant hors limites), ou {@code null}. Configuration illisible : on
     * soumet, pawaPay tranchera (comme le versement principal).
     */
    private String payoutLimitViolation(String provider, java.math.BigDecimal net) {
        PawapayProviderConfig config;
        try {
            config = client.activeConfiguration().get(provider);
        } catch (RuntimeException e) {
            log.warn("Supplément : configuration active pawaPay indisponible ({})", e.toString());
            return null;
        }
        if (config == null || !config.supportsPayout()) {
            return "OPERATOR_PAYOUT_UNAVAILABLE";
        }
        PawapayProviderConfig.Limits limits = config.payout();
        if (limits.minAmount() != null && net.compareTo(limits.minAmount()) < 0
                || limits.maxAmount() != null && net.compareTo(limits.maxAmount()) > 0) {
            return "AMOUNT_OUT_OF_PAYOUT_LIMITS";
        }
        return null;
    }

    private void alertBlocked(BidSupplementEntity s, String blocked) {
        alerts.raiseOnce("SUPP_PAYOUT_BLOCKED_" + s.getId(),
                "Versement du supplément " + s.getId() + " retenu (" + blocked + ") : il reste payé, "
                        + "le filet réessaiera",
                context(s.getId(), s.getBidId(), Map.of("reason", blocked)));
    }

    private boolean submit(BidSupplementMobileMoneyLedger.Claim claim, Destination destination) {
        UUID id = claim.supplementId();
        Optional<PawapayOperationEntity> live = operations.findLiveForSupplement(id, PawapayOperationKind.PAYOUT);
        if (live.isPresent()) {
            return reuse(claim, live.get());
        }
        PawapayOperationEntity op;
        try {
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
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("operationId", op.getId().toString());
        audit.put("provider", op.getProvider());
        audit.put("msisdnMasked", op.getMsisdnMasked());
        audit.put("accountId", destination.accountId().toString());
        ledger.auditOnly(id, "BID_SUPPLEMENT_PAYOUT_SUBMITTED", audit);
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

    /** Où verser : le compte vivant choisi. Le numéro n'apparaît jamais dans {@code toString} (logs). */
    record Destination(String msisdn, String provider, String country, UUID accountId) {
        @Override
        public String toString() {
            return "Destination[accountId=" + accountId + ", provider=" + provider + ", country=" + country + "]";
        }
    }

    /**
     * Compte de versement du voyageur du même code que le dépôt du supplément, sinon le compte repris
     * par la migration (son numéro et son propre code), dans la devise du supplément. Sans dépôt lisible
     * pour le supplément : d'abord le compte vivant qui porte encore le code ET le numéro du payout
     * principal abouti, puis la même règle qu'ailleurs. Jamais le numéro figé d'un compte retiré ou modifié.
     */
    Optional<Destination> destination(UUID bidId, UUID supplementId, UUID travelerId, String currency) {
        Optional<PawapayOperationEntity> deposit = operations.findLiveForSupplement(supplementId,
                PawapayOperationKind.DEPOSIT);
        if (deposit.isEmpty()) {
            Optional<Destination> ofPrincipal = livePrincipalAccount(bidId, travelerId, currency);
            if (ofPrincipal.isPresent()) {
                return ofPrincipal;
            }
        }
        return accounts.destinationFor(travelerId, deposit.map(PawapayOperationEntity::getProvider).orElse(null),
                currency).map(d -> new Destination(d.msisdn(), d.provider(), d.country(), d.accountId()));
    }

    /** Compte vivant dont le code et le numéro sont ceux du payout principal abouti du colis. */
    private Optional<Destination> livePrincipalAccount(UUID bidId, UUID travelerId, String currency) {
        return payments.findForBid(bidId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY)
                .flatMap(p -> operations.findLive(p.getId(), PawapayOperationKind.PAYOUT))
                .filter(op -> op.getStatus() == PawapayOperationStatus.COMPLETED)
                .flatMap(op -> accounts.activeIn(travelerId, currency).stream()
                        .filter(a -> a.getProvider().equalsIgnoreCase(op.getProvider())
                                && a.getMsisdn() != null && a.getMsisdn().equals(op.getMsisdn()))
                        .findFirst())
                .map(a -> new Destination(a.getMsisdn(), a.getProvider(), a.getCountry(), a.getId()));
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

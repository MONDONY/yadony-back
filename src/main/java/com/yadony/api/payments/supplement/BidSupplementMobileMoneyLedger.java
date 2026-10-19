package com.yadony.api.payments.supplement;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidOutEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * États du supplément payé en mobile money (pawaPay) : chaque méthode est une transaction courte,
 * sous le verrou de la ligne (et du colis quand son statut compte), sans aucun appel pawaPay. Les
 * appels réseau vivent dans {@link BidSupplementMobileMoneyRail}, {@link BidSupplementMobileMoneyPayouts}
 * et {@link BidSupplementMobileMoneyRefunds}, entre deux de ces transactions. Toute transition relit le
 * statut sous verrou : un rejeu (callback et poller pawaPay, filet, double tap) n'a aucun second effet.
 *
 * <p>Les opérations pawaPay d'un supplément portent {@code bid_supplement_id} et jamais
 * {@code payment_id} : elles ne touchent pas au paiement principal du colis.
 */
@Component
public class BidSupplementMobileMoneyLedger {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementMobileMoneyLedger.class);

    private final BidSupplementRepository supplements;
    private final BidRepository bids;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final BidSupplementWindow window;
    private final PawapayProperties pawapay;

    public BidSupplementMobileMoneyLedger(BidSupplementRepository supplements, BidRepository bids,
                                          AuditService auditService, ApplicationEventPublisher events,
                                          BidSupplementWindow window, PawapayProperties pawapay) {
        this.supplements = supplements;
        this.bids = bids;
        this.auditService = auditService;
        this.events = events;
        this.window = window;
        this.pawapay = pawapay;
    }

    /** Ce que la transaction a réservé, pour l'appel pawaPay qui suit. */
    public record Claim(UUID supplementId, UUID bidId, UUID senderId, UUID travelerId, BigDecimal net,
                        BigDecimal gross, String currency, BidSupplementStatus status, LocalDateTime expiresAt) {

        static Claim of(BidSupplementEntity s) {
            return new Claim(s.getId(), s.getBidId(), s.getSenderId(), s.getTravelerId(), s.getNetAmount(),
                    s.getGrossAmount(), s.getCurrency(), s.getStatus(), s.getExpiresAt());
        }
    }

    /**
     * Passage en {@code PAYMENT_PENDING} à l'acceptation (sous les verrous déjà pris par
     * {@code BidSupplementService.accept}). L'interrupteur pawaPay est lu ici : rail coupé, aucune
     * demande ne passe en attente d'un dépôt impossible.
     */
    public void startPayment(BidSupplementEntity s) {
        if (s.getStatus() != BidSupplementStatus.PROPOSED) {
            return;
        }
        if (!pawapay.enabled()) {
            throw PawapayErrors.disabled();
        }
        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_ACCEPTED_MM", s.getSenderId(), Map.of());
    }

    /**
     * Premier temps de l'initiation du dépôt, sous les verrous du colis puis de la demande : contrôle
     * l'appelant (expéditeur), la demande (en attente de paiement) et le colis (remise pas encore
     * terminée). Une demande encore {@code PROPOSED} et non échue y est acceptée. Une demande déjà
     * payée est rendue telle quelle (relecture idempotente) ; l'appelant renvoie alors son statut.
     * L'échéance d'une demande déjà en attente de paiement est contrôlée par l'appelant, APRÈS la
     * relecture d'un dépôt en vol.
     */
    @Transactional
    public Claim prepareDeposit(UUID bidId, UUID supplementId, UUID senderId) {
        bids.lockForUpdate(bidId);
        BidSupplementEntity s = supplements.findByIdAndBidIdForUpdate(supplementId, bidId)
                .orElseThrow(BidSupplementMobileMoneyLedger::notFound);
        if (s.getRail() != BidSupplementRail.PAWAPAY) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-mismatch",
                    "Supplement Rail Mismatch", "Ce supplément ne se paie pas en mobile money");
        }
        if (!senderId.equals(s.getSenderId())) {
            throw forbidden();
        }
        if (BidSupplementStatus.PAID_STATES.contains(s.getStatus())) {
            return Claim.of(s);
        }
        if (s.getStatus() != BidSupplementStatus.PROPOSED && s.getStatus() != BidSupplementStatus.PAYMENT_PENDING) {
            throw notPending();
        }
        BidStatus bidStatus = bids.findById(bidId).map(BidEntity::getStatus).orElse(null);
        if (bidStatus != BidStatus.ACCEPTED && bidStatus != BidStatus.HANDED_OVER) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status",
                    "Unprocessable", "Ce colis n'attend plus de réponse à un supplément");
        }
        if (s.getStatus() == BidSupplementStatus.PROPOSED) {
            requireNotExpired(s.getExpiresAt(), window.now());
            startPayment(s);
        }
        return Claim.of(s);
    }

    /** Une demande échue n'accepte plus de NOUVEAU dépôt (un dépôt en vol se relit toujours). */
    static void requireNotExpired(LocalDateTime expiresAt, LocalDateTime now) {
        if (!expiresAt.isAfter(now)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-expired", "Supplement Expired",
                    "Le délai de réponse à cette demande de supplément est dépassé");
        }
    }

    /** Lecture fraîche, sans verrou. */
    @Transactional(readOnly = true)
    public Optional<BidSupplementEntity> find(UUID supplementId) {
        return supplements.findById(supplementId);
    }

    /**
     * Dépôt du supplément confirmé par pawaPay. Demande en attente : {@code PAID}, sauf colis déjà
     * terminé sans livraison ({@code REFUND_PENDING}). Demande close (refus, échéance, colis
     * terminé) : paiement tardif, la demande garde son statut clos et le remboursement est demandé.
     * Rejeu : aucun effet.
     */
    @Transactional
    public BidSupplementCardLedger.PaidOutcome markPaid(UUID supplementId, UUID depositId, String source) {
        Optional<UUID> bidId = supplements.findBidIdById(supplementId);
        if (bidId.isEmpty()) {
            log.warn("Dépôt {} d'un supplément inconnu {} ({})", depositId, supplementId, source);
            return BidSupplementCardLedger.PaidOutcome.IGNORED;
        }
        bids.lockForUpdate(bidId.get());
        BidSupplementEntity s = supplements.findByIdAndBidIdForUpdate(supplementId, bidId.get()).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY) {
            return BidSupplementCardLedger.PaidOutcome.IGNORED;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("operationId", String.valueOf(depositId));
        extra.put("source", source);

        if (s.getStatus() == BidSupplementStatus.PAYMENT_PENDING) {
            s.setPaidAt(window.now());
            BidEntity bid = bids.findById(s.getBidId()).orElse(null);
            if (bid != null && BidSupplementService.TERMINAL_UNDELIVERED.contains(bid.getStatus())) {
                s.setStatus(BidSupplementStatus.REFUND_PENDING);
                s.setClosedReason("BID_ENDED");
                supplements.save(s);
                audit(s, "BID_SUPPLEMENT_LATE_PAYMENT_REFUND", null, extra);
                events.publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), s.getBidId(), s.getRail(),
                        s.getStatus(), "BID_ENDED"));
                return BidSupplementCardLedger.PaidOutcome.LATE_REFUND;
            }
            s.setStatus(BidSupplementStatus.PAID);
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_PAID", s.getSenderId(), extra);
            events.publishEvent(new BidSupplementPaidEvent(s.getId(), s.getBidId(), s.getSenderId(),
                    s.getTravelerId(), s.getRail(), s.getGrossAmount(), s.getCurrency()));
            return BidSupplementCardLedger.PaidOutcome.PAID;
        }
        if (BidSupplementCardLedger.CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() == null) {
            s.setPaidAt(window.now());
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_LATE_PAYMENT_REFUND", null, extra);
            events.publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), s.getBidId(), s.getRail(),
                    s.getStatus(), "LATE_PAYMENT"));
            return BidSupplementCardLedger.PaidOutcome.LATE_REFUND;
        }
        return BidSupplementCardLedger.PaidOutcome.ALREADY;
    }

    /** {@code PAID → PAYOUT_PENDING} : réserve le versement. Vide si le supplément n'est plus payé. */
    @Transactional
    public Optional<Claim> beginPayout(UUID supplementId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY || s.getStatus() != BidSupplementStatus.PAID) {
            return Optional.empty();
        }
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        supplements.save(s);
        return Optional.of(Claim.of(s));
    }

    /** Versement en cours, pour une reprise par le filet. */
    @Transactional(readOnly = true)
    public Optional<Claim> pendingPayout(UUID supplementId) {
        return supplements.findById(supplementId)
                .filter(s -> s.getRail() == BidSupplementRail.PAWAPAY
                        && s.getStatus() == BidSupplementStatus.PAYOUT_PENDING)
                .map(Claim::of);
    }

    /** {@code PAYOUT_PENDING → PAID_OUT} : versement confirmé par pawaPay. */
    @Transactional
    public boolean completePayout(UUID supplementId, UUID payoutId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY
                || s.getStatus() != BidSupplementStatus.PAYOUT_PENDING) {
            return false;
        }
        s.setStatus(BidSupplementStatus.PAID_OUT);
        s.setPaidOutAt(window.now());
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_PAID_OUT", null, Map.of("operationId", String.valueOf(payoutId)));
        events.publishEvent(new BidSupplementPaidOutEvent(s.getId(), s.getBidId(), s.getTravelerId(),
                s.getNetAmount(), s.getCurrency()));
        return true;
    }

    /** Versement refusé ou échoué (aucun argent parti) : {@code PAYOUT_PENDING → PAID}, le filet réessaie. */
    @Transactional
    public boolean revertPayout(UUID supplementId, String error) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY
                || s.getStatus() != BidSupplementStatus.PAYOUT_PENDING) {
            return false;
        }
        s.setStatus(BidSupplementStatus.PAID);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_PAYOUT_FAILED", null, Map.of("error", String.valueOf(error)));
        return true;
    }

    /**
     * Réserve le remboursement : {@code PAID → REFUND_PENDING} ; reprise d'un {@code REFUND_PENDING} ;
     * paiement tardif d'une demande close. Versement déjà parti : {@link BidSupplementCardLedger.RefundDecision#AFTER_PAYOUT}.
     */
    @Transactional
    public RefundClaim beginRefund(UUID supplementId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY) {
            return new RefundClaim(BidSupplementCardLedger.RefundDecision.NOTHING, null);
        }
        switch (s.getStatus()) {
            case PAID -> {
                s.setStatus(BidSupplementStatus.REFUND_PENDING);
                supplements.save(s);
                return new RefundClaim(BidSupplementCardLedger.RefundDecision.PROCEED, Claim.of(s));
            }
            case REFUND_PENDING -> {
                return new RefundClaim(BidSupplementCardLedger.RefundDecision.PROCEED, Claim.of(s));
            }
            case PAYOUT_PENDING, PAID_OUT -> {
                return new RefundClaim(BidSupplementCardLedger.RefundDecision.AFTER_PAYOUT, Claim.of(s));
            }
            default -> {
                boolean latePayment = BidSupplementCardLedger.CLOSED_UNPAID.contains(s.getStatus())
                        && s.getPaidAt() != null && s.getRefundedAt() == null;
                return latePayment ? new RefundClaim(BidSupplementCardLedger.RefundDecision.PROCEED, Claim.of(s))
                        : new RefundClaim(BidSupplementCardLedger.RefundDecision.NOTHING, null);
            }
        }
    }

    /** Décision d'un remboursement mobile money. */
    public record RefundClaim(BidSupplementCardLedger.RefundDecision decision, Claim claim) {
    }

    /**
     * Remboursement confirmé par pawaPay : {@code REFUND_PENDING → REFUNDED} ; un paiement tardif
     * passe aussi en {@code REFUNDED}. Sans effet si c'est déjà fait.
     */
    @Transactional
    public boolean completeRefund(UUID supplementId, UUID refundId, String source) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.PAWAPAY || s.getRefundedAt() != null) {
            return false;
        }
        boolean refundable = s.getStatus() == BidSupplementStatus.REFUND_PENDING
                || s.getStatus() == BidSupplementStatus.PAID
                || (BidSupplementCardLedger.CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() != null);
        if (!refundable) {
            return false;
        }
        s.setStatus(BidSupplementStatus.REFUNDED);
        s.setRefundedAt(window.now());
        supplements.save(s);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("operationId", String.valueOf(refundId));
        extra.put("source", source);
        audit(s, "BID_SUPPLEMENT_REFUNDED", null, extra);
        events.publishEvent(new BidSupplementRefundedEvent(s.getId(), s.getBidId(), s.getSenderId(),
                s.getGrossAmount(), s.getCurrency(), false));
        return true;
    }

    /** Trace d'un événement sans transition (dépôt échoué, soumission, refus…). */
    @Transactional
    public void auditOnly(UUID supplementId, String action, Map<String, Object> extra) {
        supplements.findById(supplementId).ifPresent(s -> audit(s, action, null, extra));
    }

    private Optional<BidSupplementEntity> lock(UUID supplementId) {
        return supplements.findBidIdById(supplementId)
                .flatMap(bidId -> supplements.findByIdAndBidIdForUpdate(supplementId, bidId));
    }

    private void audit(BidSupplementEntity s, String action, UUID actorId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", s.getBidId().toString());
        payload.put("rail", s.getRail().name());
        payload.put("currency", s.getCurrency());
        payload.put("net", s.getNetAmount());
        payload.put("commission", s.getCommissionAmount());
        payload.put("gross", s.getGrossAmount());
        payload.put("status", s.getStatus().name());
        payload.putAll(extra);
        auditService.log(BidSupplementService.ENTITY, s.getId(), action, actorId, payload);
    }

    static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "supplement-not-found", "Supplement Not Found",
                "Demande de supplément introuvable");
    }

    static YadonyBusinessException forbidden() {
        return new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "Vous n'êtes pas partie à ce colis");
    }

    static YadonyBusinessException notPending() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-not-pending", "Supplement Not Pending",
                "Cette demande de supplément n'attend plus de paiement");
    }
}

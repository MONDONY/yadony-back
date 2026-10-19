package com.yadony.api.payments.supplement;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidOutEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * États du supplément payé par carte : chaque méthode est une transaction courte, sous le verrou de
 * la ligne (et du colis quand le statut du colis compte), sans aucun appel Stripe. Les appels
 * réseau vivent dans {@link BidSupplementCardRail}, {@link BidSupplementCardPayouts} et
 * {@link BidSupplementCardRefunds}, entre deux de ces transactions (modèle
 * {@code PaymentSplitService.claim/execute}). Toute transition relit le statut sous verrou : un
 * rejeu (webhook, filet, double tap) ne produit aucun second effet.
 */
@Component
public class BidSupplementCardLedger {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCardLedger.class);

    /** Demandes closes sans paiement : un paiement qui y arrive est tardif, donc rendu. */
    static final Set<BidSupplementStatus> CLOSED_UNPAID = EnumSet.of(BidSupplementStatus.DECLINED,
            BidSupplementStatus.EXPIRED, BidSupplementStatus.WITHDRAWN, BidSupplementStatus.CANCELLED);

    private final BidSupplementRepository supplements;
    private final BidRepository bids;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final BidSupplementWindow window;

    public BidSupplementCardLedger(BidSupplementRepository supplements, BidRepository bids,
                                   AuditService auditService, ApplicationEventPublisher events,
                                   BidSupplementWindow window) {
        this.supplements = supplements;
        this.bids = bids;
        this.auditService = auditService;
        this.events = events;
        this.window = window;
    }

    /** Issue de l'enregistrement d'un paiement réussi. */
    public enum PaidOutcome {
        /** Demande en attente de paiement : désormais payée. */
        PAID,
        /** Paiement arrivé sur une demande close ou un colis terminé : il sera remboursé. */
        LATE_REFUND,
        /** Déjà enregistré (rejeu) : aucun effet. */
        ALREADY,
        /** Supplément introuvable, d'un autre rail, ou PaymentIntent qui n'est pas le sien. */
        IGNORED
    }

    /** Ce que la transaction a réservé, pour l'appel Stripe qui suit. */
    public record Claim(UUID supplementId, UUID bidId, UUID senderId, UUID travelerId, BigDecimal net,
                       BigDecimal gross, String currency, String paymentIntentId, String chargeId, String refundId) {

        static Claim of(BidSupplementEntity s) {
            return new Claim(s.getId(), s.getBidId(), s.getSenderId(), s.getTravelerId(), s.getNetAmount(),
                    s.getGrossAmount(), s.getCurrency(), s.getStripePaymentIntentId(), s.getStripeChargeId(),
                    s.getStripeRefundId());
        }
    }

    /** Décision d'un remboursement. */
    public enum RefundDecision { PROCEED, AFTER_PAYOUT, NOTHING }

    public record RefundClaim(RefundDecision decision, Claim claim) {
    }

    /**
     * Passage en {@code PAYMENT_PENDING} de la première acceptation (sous les verrous déjà pris par
     * {@code BidSupplementService.accept}) : tentative n° 1 si aucune n'a eu lieu.
     */
    public void startPayment(BidSupplementEntity s) {
        if (s.getStatus() == BidSupplementStatus.PROPOSED) {
            s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
            if (s.getStripePiAttempt() == 0) {
                s.setStripePiAttempt(1);
            }
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_ACCEPTED_CARD", s.getSenderId(), Map.of());
        }
    }

    /** Lecture fraîche, sans verrou. */
    @Transactional(readOnly = true)
    public Optional<BidSupplementEntity> find(UUID supplementId) {
        return supplements.findById(supplementId);
    }

    /**
     * Enregistre le PaymentIntent créé pour la tentative {@code attempt}. Sans effet si une autre
     * requête l'a déjà enregistré (même clé d'idempotence, donc même PaymentIntent) ou si la
     * tentative a changé entre-temps. Renvoie l'état relu.
     */
    @Transactional
    public BidSupplementEntity storePaymentIntent(UUID supplementId, int attempt, String paymentIntentId) {
        BidSupplementEntity s = lock(supplementId).orElseThrow();
        if (s.getStripePaymentIntentId() == null && s.getStripePiAttempt() == attempt) {
            s.setStripePaymentIntentId(paymentIntentId);
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_PI_CREATED", s.getSenderId(), Map.of("piId", paymentIntentId,
                    "attempt", attempt));
        }
        return s;
    }

    /**
     * PaymentIntent annulé (abandon, expiration Stripe) : la tentative suivante en créera un neuf,
     * sous une nouvelle clé. Sans effet si le supplément a changé entre-temps.
     */
    @Transactional
    public boolean renewAttempt(UUID supplementId, String canceledPaymentIntentId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getStatus() != BidSupplementStatus.PAYMENT_PENDING
                || !java.util.Objects.equals(s.getStripePaymentIntentId(), canceledPaymentIntentId)) {
            return false;
        }
        s.setStripePiAttempt(s.getStripePiAttempt() + 1);
        s.setStripePaymentIntentId(null);
        supplements.save(s);
        return true;
    }

    /**
     * Paiement réussi (webhook {@code payment_intent.succeeded}, {@code confirm-payment}, filet).
     * Demande en attente : {@code PAID}, sauf si le colis est déjà terminé sans livraison
     * ({@code REFUND_PENDING}). Demande close : paiement tardif, la demande garde son statut clos et
     * le remboursement est demandé. Rejeu : aucun effet.
     */
    @Transactional
    public PaidOutcome markPaid(UUID supplementId, String paymentIntentId, String chargeId, String source) {
        Optional<UUID> bidId = supplements.findBidIdById(supplementId);
        if (bidId.isEmpty()) {
            log.warn("Paiement {} d'un supplément inconnu {} ({})", paymentIntentId, supplementId, source);
            return PaidOutcome.IGNORED;
        }
        bids.lockForUpdate(bidId.get());
        BidSupplementEntity s = supplements.findByIdAndBidIdForUpdate(supplementId, bidId.get()).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.STRIPE) {
            return PaidOutcome.IGNORED;
        }
        if (s.getStripePaymentIntentId() != null && paymentIntentId != null
                && !s.getStripePaymentIntentId().equals(paymentIntentId)) {
            log.error("Supplément {} : PaymentIntent {} réussi alors que le supplément porte {}",
                    supplementId, paymentIntentId, s.getStripePaymentIntentId());
            return PaidOutcome.IGNORED;
        }
        if (s.getStripePaymentIntentId() == null) {
            // Le webhook peut arriver avant que la création n'ait enregistré le PaymentIntent.
            s.setStripePaymentIntentId(paymentIntentId);
        }
        if (s.getStripeChargeId() == null && chargeId != null) {
            s.setStripeChargeId(chargeId);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("piId", String.valueOf(paymentIntentId));
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
                return PaidOutcome.LATE_REFUND;
            }
            s.setStatus(BidSupplementStatus.PAID);
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_PAID", s.getSenderId(), extra);
            events.publishEvent(new BidSupplementPaidEvent(s.getId(), s.getBidId(), s.getSenderId(),
                    s.getTravelerId(), s.getRail(), s.getGrossAmount(), s.getCurrency()));
            return PaidOutcome.PAID;
        }
        if (CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() == null) {
            s.setPaidAt(window.now());
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_LATE_PAYMENT_REFUND", null, extra);
            events.publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), s.getBidId(), s.getRail(),
                    s.getStatus(), "LATE_PAYMENT"));
            return PaidOutcome.LATE_REFUND;
        }
        supplements.save(s);
        return PaidOutcome.ALREADY;
    }

    /** {@code PAID → PAYOUT_PENDING} : réserve le versement. Vide si le supplément n'est plus payé. */
    @Transactional
    public Optional<Claim> beginPayout(UUID supplementId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.STRIPE || s.getStatus() != BidSupplementStatus.PAID) {
            return Optional.empty();
        }
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_PAYOUT_SUBMITTED", null, Map.of());
        return Optional.of(Claim.of(s));
    }

    /** Versement en cours, pour une reprise par le filet. */
    @Transactional(readOnly = true)
    public Optional<Claim> pendingPayout(UUID supplementId) {
        return supplements.findById(supplementId)
                .filter(s -> s.getRail() == BidSupplementRail.STRIPE
                        && s.getStatus() == BidSupplementStatus.PAYOUT_PENDING)
                .map(Claim::of);
    }

    /** {@code PAYOUT_PENDING → PAID_OUT} : Transfer créé (ou retrouvé). */
    @Transactional
    public boolean completePayout(UUID supplementId, String transferId, String chargeId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getStatus() != BidSupplementStatus.PAYOUT_PENDING) {
            return false;
        }
        s.setStatus(BidSupplementStatus.PAID_OUT);
        s.setStripeTransferId(transferId);
        if (s.getStripeChargeId() == null) {
            s.setStripeChargeId(chargeId);
        }
        s.setPaidOutAt(window.now());
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_PAID_OUT", null, Map.of("transferId", transferId));
        events.publishEvent(new BidSupplementPaidOutEvent(s.getId(), s.getBidId(), s.getTravelerId(),
                s.getNetAmount(), s.getCurrency()));
        return true;
    }

    /** Échec du Transfer : {@code PAYOUT_PENDING → PAID}, le filet réessaie. */
    @Transactional
    public void revertPayout(UUID supplementId, String error) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getStatus() != BidSupplementStatus.PAYOUT_PENDING) {
            return;
        }
        s.setStatus(BidSupplementStatus.PAID);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_PAYOUT_FAILED", null, Map.of("error", String.valueOf(error)));
    }

    /**
     * Réserve le remboursement : {@code PAID → REFUND_PENDING} ; reprise d'un
     * {@code REFUND_PENDING} ; paiement tardif d'une demande close. Versement déjà parti :
     * {@link RefundDecision#AFTER_PAYOUT} (pas d'inversion de Transfer en v1, alerte).
     */
    @Transactional
    public RefundClaim beginRefund(UUID supplementId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRail() != BidSupplementRail.STRIPE) {
            return new RefundClaim(RefundDecision.NOTHING, null);
        }
        switch (s.getStatus()) {
            case PAID -> {
                s.setStatus(BidSupplementStatus.REFUND_PENDING);
                supplements.save(s);
                return new RefundClaim(RefundDecision.PROCEED, Claim.of(s));
            }
            case REFUND_PENDING -> {
                return new RefundClaim(RefundDecision.PROCEED, Claim.of(s));
            }
            case PAYOUT_PENDING, PAID_OUT -> {
                return new RefundClaim(RefundDecision.AFTER_PAYOUT, Claim.of(s));
            }
            default -> {
                boolean latePayment = CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() != null
                        && s.getRefundedAt() == null;
                return latePayment ? new RefundClaim(RefundDecision.PROCEED, Claim.of(s))
                        : new RefundClaim(RefundDecision.NOTHING, null);
            }
        }
    }

    /**
     * Remboursement fait (créé par nous, ou constaté par {@code charge.refunded}) :
     * {@code REFUND_PENDING → REFUNDED} ; un paiement tardif est marqué remboursé et la demande passe
     * en {@code REFUNDED}. Sans effet si c'est déjà fait.
     */
    @Transactional
    public boolean completeRefund(UUID supplementId, String refundId, String source) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null || s.getRefundedAt() != null) {
            return false;
        }
        boolean refundable = s.getStatus() == BidSupplementStatus.REFUND_PENDING
                || s.getStatus() == BidSupplementStatus.PAID
                || (CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() != null);
        if (!refundable) {
            return false;
        }
        s.setStatus(BidSupplementStatus.REFUNDED);
        s.setStripeRefundId(refundId);
        s.setRefundedAt(window.now());
        supplements.save(s);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("refundId", String.valueOf(refundId));
        extra.put("source", source);
        audit(s, "BID_SUPPLEMENT_REFUNDED", null, extra);
        events.publishEvent(new BidSupplementRefundedEvent(s.getId(), s.getBidId(), s.getSenderId(),
                s.getGrossAmount(), s.getCurrency(), false));
        return true;
    }

    /**
     * Refund du supplément échoué chez Stripe ({@code charge.refund.updated}, statut
     * {@code failed} ou {@code canceled}) : l'argent n'est pas rendu, le supplément ne doit pas
     * rester {@code REFUNDED}. Il redevient « à rembourser » :
     * <ul>
     *   <li>{@code REFUND_PENDING} en règle générale (le filet relance le remboursement) ;</li>
     *   <li>{@code CANCELLED} payé et non remboursé (paiement tardif, que le filet relance aussi)
     *       si une autre demande est active sur le colis : un second {@code REFUND_PENDING}
     *       violerait l'index {@code uq_bid_supplements_one_active}.</li>
     * </ul>
     * Le refund échoué reste dans {@code stripe_refund_id} : le prochain essai change de clé
     * d'idempotence (sinon Stripe rendrait le même refund échoué). Un vieux refund échoué, autre
     * que celui enregistré, ne défait rien.
     *
     * @return vrai si le supplément est désormais à rembourser.
     */
    @Transactional
    public boolean refundFailed(UUID supplementId, String refundId) {
        BidSupplementEntity s = lock(supplementId).orElse(null);
        if (s == null) {
            return false;
        }
        boolean sameRefund = s.getStripeRefundId() == null || s.getStripeRefundId().equals(refundId);
        boolean latePayment = CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() != null
                && s.getRefundedAt() == null;
        if (s.getStatus() == BidSupplementStatus.REFUNDED && sameRefund) {
            boolean otherActive = supplements.existsByBidIdAndStatusIn(s.getBidId(), BidSupplementStatus.ACTIVE);
            s.setStatus(otherActive ? BidSupplementStatus.CANCELLED : BidSupplementStatus.REFUND_PENDING);
            s.setRefundedAt(null);
        } else if (!(s.getStatus() == BidSupplementStatus.REFUND_PENDING || latePayment) || !sameRefund) {
            return false;
        }
        s.setStripeRefundId(refundId);
        supplements.save(s);
        audit(s, "BID_SUPPLEMENT_REFUND_FAILED", null, Map.of("refundId", String.valueOf(refundId)));
        return true;
    }

    /** Trace d'un événement sans transition (échec, litige…). */
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
}

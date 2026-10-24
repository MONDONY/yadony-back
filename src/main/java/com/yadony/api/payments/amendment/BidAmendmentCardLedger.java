package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * États du rail carte d'une modification : chaque méthode publique est une transaction courte, sans aucun appel Stripe
 * (ceux-ci vivent dans {@link BidAmendmentCardRail} et {@link BidAmendmentCardRefunds}, entre deux de ces
 * transactions). Toute transition relit l'état sous verrou : un rejeu (webhook, confirmation de l'app, filet) ne produit
 * aucun second effet.
 *
 * <p>Verrous, toujours dans cet ordre : le colis, puis le trajet quand la transition peut clore la modification (kilos
 * rendus), puis la modification.
 */
@Component
public class BidAmendmentCardLedger {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCardLedger.class);

    /** Statut Stripe d'une PaymentIntent autorisée, prête à être capturée. */
    static final String REQUIRES_CAPTURE = "requires_capture";

    /** Closes sans avoir été acquises : un montant capturé qui y arrive est tardif, donc rendu. */
    static final Set<BidAmendmentStatus> CLOSED_UNACCEPTED = EnumSet.of(BidAmendmentStatus.DECLINED,
            BidAmendmentStatus.EXPIRED, BidAmendmentStatus.CANCELLED);

    /** Issue de l'enregistrement d'un montant capturé. */
    public enum CaptureOutcome {
        /** Modification proposée : désormais acquise. */
        ACCEPTED,
        /** Capture arrivée sur une modification close entre-temps : le montant sera rendu. */
        LATE_REFUND,
        /** Déjà enregistrée (rejeu) : aucun effet. */
        ALREADY,
        /** Modification introuvable, d'un autre rail, ou PaymentIntent qui n'est pas la sienne. */
        IGNORED
    }

    /** Issue d'un remboursement intégral constaté par Stripe ({@code charge.refunded}). */
    public enum RefundOutcome {
        /** Remboursement attendu : la modification est remboursée. */
        REFUNDED,
        /** Déjà enregistré (rejeu) : aucun effet. */
        ALREADY,
        /** Remboursée alors qu'elle est encore acquise ou ouverte (Dashboard) : à vérifier par un admin. */
        LIVE,
        /** Modification introuvable ou d'un autre rail. */
        IGNORED
    }

    /** Décision d'un remboursement. */
    public enum RefundDecision { PROCEED, NOTHING }

    /** Ce qu'il faut pour rembourser hors transaction. */
    public record RefundClaim(RefundDecision decision, UUID amendmentId, UUID bidId, String paymentIntentId,
                              String previousRefundId) {

        static RefundClaim nothing() {
            return new RefundClaim(RefundDecision.NOTHING, null, null, null, null);
        }
    }

    private final BidAmendmentRepository amendments;
    private final BidAmendmentService service;
    private final BidRepository bids;
    private final AnnouncementRepository announcements;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final BidAmendmentWindow window;
    private final AdminAlertEscalator alerts;

    public BidAmendmentCardLedger(BidAmendmentRepository amendments, BidAmendmentService service, BidRepository bids,
                                  AnnouncementRepository announcements, AuditService auditService,
                                  ApplicationEventPublisher events, BidAmendmentWindow window,
                                  AdminAlertEscalator alerts) {
        this.amendments = amendments;
        this.service = service;
        this.bids = bids;
        this.announcements = announcements;
        this.auditService = auditService;
        this.events = events;
        this.window = window;
        this.alerts = alerts;
    }

    /** Lecture fraîche, sans verrou. */
    @Transactional(readOnly = true)
    public Optional<BidAmendmentEntity> find(UUID amendmentId) {
        return amendments.findById(amendmentId);
    }

    /**
     * Enregistre la PaymentIntent tout juste créée. Sans effet si une PaymentIntent est déjà enregistrée (même clé
     * d'idempotence, donc la même). Renvoie l'état relu : l'appelant annule la PaymentIntent si la modification a été
     * close pendant sa création.
     */
    @Transactional
    public Optional<BidAmendmentEntity> storePaymentIntent(UUID amendmentId, String paymentIntentId) {
        Optional<BidAmendmentEntity> found = lock(amendmentId, false).filter(this::card);
        found.ifPresent(a -> {
            if (a.getStripePaymentIntentId() == null) {
                a.setStripePaymentIntentId(paymentIntentId);
                amendments.save(a);
            }
        });
        return found;
    }

    /**
     * La PaymentIntent n'a pas pu être créée : la demande ne peut plus être autorisée. Elle est annulée tout de suite
     * ({@code AUTHORIZATION_NOT_CONFIRMED}), ses kilos rendus, pour que l'expéditeur puisse recommencer.
     */
    @Transactional
    public void abandonAuthorization(UUID amendmentId) {
        lock(amendmentId, true).filter(this::card)
                .filter(a -> a.getStatus() == BidAmendmentStatus.AUTHORIZATION_PENDING)
                .ifPresent(a -> service.cancelOpen(a, BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED));
    }

    /**
     * Issue de l'enregistrement d'une autorisation.
     *
     * @param amendment            état relu.
     * @param releaseAuthorization la PaymentIntent vient d'être autorisée alors que la demande était déjà close (et son
     *                             autorisation pas encore rendue) : l'appelant doit la rendre, hors transaction.
     */
    public record Authorization(BidAmendmentEntity amendment, boolean releaseAuthorization) {
    }

    /**
     * Issue de l'autorisation (confirmation de l'app, webhook {@code amount_capturable_updated}). PaymentIntent
     * {@code requires_capture} : la demande est présentée au voyageur ({@link BidAmendmentService#markProposed}), sauf
     * si l'étape d'autorisation est échue (la demande est alors close comme par le filet, l'autorisation sera rendue
     * par l'écouteur de la clôture) ou si le colis n'est plus accepté. Demande déjà close : rien ne change ici, mais
     * l'appelant doit rendre l'autorisation tardive ({@link Authorization#releaseAuthorization}). Idempotent.
     *
     * @return vide si la modification est introuvable ou d'un autre rail.
     */
    @Transactional
    public Optional<Authorization> authorize(UUID amendmentId, String paymentIntentId, String piStatus,
                                             String source) {
        BidAmendmentEntity a = lock(amendmentId, true).filter(this::card).orElse(null);
        if (a == null) {
            return Optional.empty();
        }
        if (!REQUIRES_CAPTURE.equals(piStatus) || (!Objects.equals(a.getStripePaymentIntentId(), paymentIntentId)
                && a.getStripePaymentIntentId() != null)) {
            return Optional.of(new Authorization(a, false));
        }
        if (a.getStatus() != BidAmendmentStatus.AUTHORIZATION_PENDING) {
            // Autorisation arrivée sur une demande déjà close (retirée, échue, colis terminé) : à rendre.
            boolean late = CLOSED_UNACCEPTED.contains(a.getStatus()) && a.getStripeChargeId() == null
                    && a.getAuthorizationReleasedAt() == null && a.getStripePaymentIntentId() != null;
            return Optional.of(new Authorization(a, late));
        }
        adopt(a, paymentIntentId);
        if (window.isLapsed(a)) {
            log.info("Modification {} : autorisation {} arrivée après l'échéance ({}), close", a.getId(),
                    paymentIntentId, source);
            service.expireUnderBidLock(a);
            return Optional.of(new Authorization(a, false));
        }
        BidEntity bid = bids.findById(a.getBidId()).orElse(null);
        if (bid == null || bid.getStatus() != BidStatus.ACCEPTED) {
            service.cancelOpen(a, BidAmendmentClosedReason.BID_ENDED);
            return Optional.of(new Authorization(a, false));
        }
        service.markProposed(a);
        return Optional.of(new Authorization(a, false));
    }

    /**
     * Autorisation d'une modification close rendue (PaymentIntent annulée, ou constatée déjà annulée) : marqueur posé,
     * le filet ne relit plus Stripe pour elle. Idempotent.
     */
    @Transactional
    public void authorizationReleased(UUID amendmentId, String piStatus) {
        lock(amendmentId, false).filter(this::card).filter(a -> a.getAuthorizationReleasedAt() == null)
                .ifPresent(a -> {
                    a.setAuthorizationReleasedAt(window.now());
                    amendments.save(a);
                    audit(a, "BID_AMENDMENT_AUTHORIZATION_RELEASED", null, Map.of("piId",
                            String.valueOf(a.getStripePaymentIntentId()), "piStatus", String.valueOf(piStatus)));
                });
    }

    /**
     * Réponse 5xx de Stripe à la capture : l'essai suivant change de clé d'idempotence. Sinon Stripe rejouerait la même
     * erreur pendant 24 h. Sans risque de double débit : une PaymentIntent ne se capture qu'une fois, une seconde
     * capture est refusée ({@code payment_intent_unexpected_state}) et la PaymentIntent relue, déjà {@code succeeded}.
     */
    @Transactional
    public void nextCaptureAttempt(UUID amendmentId) {
        lock(amendmentId, false).filter(this::card).ifPresent(a -> {
            a.setCaptureAttempt(a.getCaptureAttempt() + 1);
            amendments.save(a);
        });
    }

    /**
     * Montant capturé (capture de l'acceptation, webhook {@code payment_intent.succeeded}, annulation refusée car déjà
     * capturé). Modification proposée : acquise, charge enregistrée. Close entre-temps (refus, annulation, échéance) :
     * capture tardive, la modification garde son statut clos et le remboursement est demandé une seule fois (premier
     * enregistrement de la charge). Rejeu : aucun effet. Sans charge : ignorée, l'appelant relit la PaymentIntent.
     */
    @Transactional
    public CaptureOutcome captured(UUID amendmentId, String paymentIntentId, String chargeId, String source) {
        if (chargeId == null || chargeId.isBlank()) {
            // La charge est le repère d'un montant capturé (remboursement, versement) : l'appelant la relit d'abord.
            log.error("Modification {} : capture {} enregistrée sans charge ({}), ignorée", amendmentId,
                    paymentIntentId, source);
            return CaptureOutcome.IGNORED;
        }
        BidAmendmentEntity a = lock(amendmentId, true).filter(this::card).orElse(null);
        if (a == null || !adopt(a, paymentIntentId)) {
            return CaptureOutcome.IGNORED;
        }
        boolean firstRecord = a.getStripeChargeId() == null;
        if (firstRecord) {
            a.setStripeChargeId(chargeId);
        }
        amendments.save(a);
        if (a.getStatus() == BidAmendmentStatus.PROPOSED) {
            service.markAccepted(a);
            return CaptureOutcome.ACCEPTED;
        }
        if (CLOSED_UNACCEPTED.contains(a.getStatus()) && a.getRefundedAt() == null) {
            if (!firstRecord) {
                return CaptureOutcome.ALREADY;
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("piId", String.valueOf(paymentIntentId));
            extra.put("source", source);
            audit(a, "BID_AMENDMENT_LATE_CAPTURE_REFUND", null, extra);
            events.publishEvent(new BidAmendmentRefundRequestedEvent(a.getId(), a.getBidId(), a.getRail(),
                    "LATE_CAPTURE"));
            log.warn("Modification {} : montant capturé après sa clôture ({}), remboursement demandé", a.getId(),
                    a.getStatus());
            return CaptureOutcome.LATE_REFUND;
        }
        if (a.getStatus() == BidAmendmentStatus.AUTHORIZATION_PENDING) {
            // Capture manuelle : rien ne capture sans acceptation. Ne doit jamais arriver.
            alerts.raiseOnce("AMEND_UNEXPECTED_CAPT_" + a.getId(),
                    "Montant de la modification " + a.getId() + " capturé alors qu'elle n'a pas été présentée au "
                            + "voyageur : à rembourser à la main",
                    Map.of("amendmentId", a.getId().toString(), "bidId", a.getBidId().toString(),
                            "paymentIntentId", String.valueOf(paymentIntentId)));
        }
        return CaptureOutcome.ALREADY;
    }

    /**
     * Capture refusée : l'autorisation n'est plus capturable (expirée, annulée). La modification proposée est annulée
     * ({@code CAPTURE_FAILED}), ses kilos rendus, les deux parties prévenues par {@code BidAmendmentClosedEvent}.
     *
     * @return vrai si la modification vient d'être annulée.
     */
    @Transactional
    public boolean captureFailed(UUID amendmentId, String reason) {
        BidAmendmentEntity a = lock(amendmentId, true).filter(this::card).orElse(null);
        if (a == null || a.getStatus() != BidAmendmentStatus.PROPOSED) {
            return false;
        }
        log.warn("Modification {} : capture impossible ({}), annulée", a.getId(), reason);
        return service.cancelOpen(a, BidAmendmentClosedReason.CAPTURE_FAILED);
    }

    /**
     * PaymentIntent annulée chez Stripe (autorisation expirée, annulation depuis le Dashboard) : la demande ouverte ne
     * peut plus être payée. En attente d'autorisation : {@code AUTHORIZATION_NOT_CONFIRMED} ; proposée :
     * {@code CAPTURE_FAILED}. Une demande déjà close (c'est nous qui l'avons annulée) ne bouge pas.
     */
    @Transactional
    public boolean paymentIntentCanceled(UUID amendmentId, String paymentIntentId) {
        BidAmendmentEntity a = lock(amendmentId, true).filter(this::card).orElse(null);
        if (a == null || a.getStripeChargeId() != null || !adopt(a, paymentIntentId)) {
            return false;
        }
        return switch (a.getStatus()) {
            case AUTHORIZATION_PENDING -> service.cancelOpen(a, BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED);
            case PROPOSED -> service.cancelOpen(a, BidAmendmentClosedReason.CAPTURE_FAILED);
            default -> false;
        };
    }

    /**
     * Réserve le remboursement : modification en {@code REFUND_PENDING} (non apportée, colis terminé), ou capture
     * tardive d'une modification close et pas encore remboursée.
     */
    @Transactional
    public RefundClaim beginRefund(UUID amendmentId) {
        BidAmendmentEntity a = lock(amendmentId, false).filter(this::card).orElse(null);
        if (a == null || a.getStripePaymentIntentId() == null || a.getRefundedAt() != null) {
            return RefundClaim.nothing();
        }
        boolean lateCapture = CLOSED_UNACCEPTED.contains(a.getStatus()) && a.getStripeChargeId() != null;
        if (a.getStatus() != BidAmendmentStatus.REFUND_PENDING && !lateCapture) {
            return RefundClaim.nothing();
        }
        return new RefundClaim(RefundDecision.PROCEED, a.getId(), a.getBidId(), a.getStripePaymentIntentId(),
                a.getStripeRefundId());
    }

    /**
     * Refund créé (ou retrouvé) chez Stripe : enregistré. La modification ne passe {@code REFUNDED} qu'à sa
     * confirmation ({@code charge.refunded}, {@link #refunded}).
     */
    @Transactional
    public void refundSubmitted(UUID amendmentId, String refundId) {
        lock(amendmentId, false).filter(this::card).filter(a -> a.getRefundedAt() == null)
                .filter(a -> !Objects.equals(a.getStripeRefundId(), refundId))
                .ifPresent(a -> {
                    a.setStripeRefundId(refundId);
                    amendments.save(a);
                    audit(a, "BID_AMENDMENT_REFUND_SUBMITTED", null, Map.of("refundId", String.valueOf(refundId)));
                });
    }

    /**
     * Remboursement intégral constaté par Stripe ({@code charge.refunded}) : {@code REFUND_PENDING → REFUNDED} ; une
     * capture tardive est marquée remboursée (la modification garde son statut clos). L'expéditeur est prévenu
     * ({@link BidAmendmentRefundedEvent}, brut). Remboursée alors qu'elle vit encore : {@link RefundOutcome#LIVE}.
     */
    @Transactional
    public RefundOutcome refunded(UUID amendmentId, String refundId, String source) {
        BidAmendmentEntity a = lock(amendmentId, false).filter(this::card).orElse(null);
        if (a == null) {
            return RefundOutcome.IGNORED;
        }
        if (a.getRefundedAt() != null || a.getStatus() == BidAmendmentStatus.REFUNDED) {
            return RefundOutcome.ALREADY;
        }
        boolean lateCapture = CLOSED_UNACCEPTED.contains(a.getStatus()) && a.getStripeChargeId() != null;
        if (a.getStatus() != BidAmendmentStatus.REFUND_PENDING && !lateCapture) {
            return CLOSED_UNACCEPTED.contains(a.getStatus()) ? RefundOutcome.ALREADY : RefundOutcome.LIVE;
        }
        BidAmendmentStatus previous = a.getStatus();
        if (previous == BidAmendmentStatus.REFUND_PENDING) {
            a.setStatus(BidAmendmentStatus.REFUNDED);
        }
        a.setRefundedAt(window.now());
        if (refundId != null) {
            a.setStripeRefundId(refundId);
        }
        amendments.save(a);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("previousStatus", previous.name());
        extra.put("refundId", String.valueOf(refundId));
        extra.put("source", source);
        audit(a, "BID_AMENDMENT_REFUNDED", null, extra);
        events.publishEvent(new BidAmendmentRefundedEvent(a.getId(), a.getBidId(), a.getSenderId(),
                a.getGrossAmount(), a.getCurrency(), false));
        return RefundOutcome.REFUNDED;
    }

    /**
     * Refund échoué chez Stripe après coup ({@code charge.refund.updated}, {@code failed} ou {@code canceled}) :
     * l'argent n'est pas rendu. La modification redevient « à rembourser » ({@code REFUNDED → REFUND_PENDING}, ou
     * capture tardive de nouveau non remboursée) ; le Refund échoué reste enregistré pour que le prochain essai change de
     * clé. Un vieux Refund, autre que celui enregistré, ne défait rien.
     *
     * @return vrai si la modification est de nouveau à rembourser.
     */
    @Transactional
    public boolean refundFailed(UUID amendmentId, String refundId) {
        BidAmendmentEntity a = lock(amendmentId, false).filter(this::card).orElse(null);
        if (a == null || (a.getStripeRefundId() != null && !a.getStripeRefundId().equals(refundId))) {
            return false;
        }
        boolean pending = a.getStatus() == BidAmendmentStatus.REFUND_PENDING;
        boolean refunded = a.getStatus() == BidAmendmentStatus.REFUNDED;
        boolean lateCapture = CLOSED_UNACCEPTED.contains(a.getStatus()) && a.getStripeChargeId() != null;
        if (!pending && !refunded && !lateCapture) {
            return false;
        }
        if (refunded) {
            a.setStatus(BidAmendmentStatus.REFUND_PENDING);
        }
        a.setRefundedAt(null);
        a.setStripeRefundId(refundId);
        amendments.save(a);
        audit(a, "BID_AMENDMENT_REFUND_FAILED", null, Map.of("refundId", String.valueOf(refundId)));
        return true;
    }

    /** Trace d'un événement sans transition (autorisation rendue, litige…). */
    @Transactional
    public void auditOnly(UUID amendmentId, String action, Map<String, Object> extra) {
        amendments.findById(amendmentId).ifPresent(a -> audit(a, action, null, extra));
    }

    /**
     * La PaymentIntent est-elle celle de la modification ? Une seule par modification (clé {@code amendment-pi-<id>}) :
     * celle enregistrée, ou, tant qu'aucune ne l'est (le webhook peut devancer l'enregistrement), celle du webhook, qui
     * est alors adoptée.
     */
    private boolean adopt(BidAmendmentEntity a, String paymentIntentId) {
        if (paymentIntentId == null) {
            return false;
        }
        if (a.getStripePaymentIntentId() == null) {
            a.setStripePaymentIntentId(paymentIntentId);
            return true;
        }
        if (!a.getStripePaymentIntentId().equals(paymentIntentId)) {
            log.error("Modification {} : PaymentIntent {} reçue alors qu'elle porte {}", a.getId(), paymentIntentId,
                    a.getStripePaymentIntentId());
            return false;
        }
        return true;
    }

    private boolean card(BidAmendmentEntity a) {
        return a.getRail() == BidSupplementRail.STRIPE;
    }

    private Optional<BidAmendmentEntity> lock(UUID amendmentId, boolean withTrip) {
        Optional<UUID> bidId = amendments.findBidIdById(amendmentId);
        if (bidId.isEmpty() || bids.lockForUpdate(bidId.get()) == 0) {
            return Optional.empty();
        }
        if (withTrip) {
            bids.findById(bidId.get()).map(BidEntity::getAnnouncementId).flatMap(announcements::findByIdForUpdate);
        }
        return amendments.findByIdAndBidIdForUpdate(amendmentId, bidId.get());
    }

    /** Une entrée par transition : comme le service, ni net ni commission. */
    void audit(BidAmendmentEntity a, String action, UUID actorId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", a.getBidId().toString());
        payload.put("amendmentId", a.getId().toString());
        payload.put("rail", a.getRail().name());
        payload.put("currency", a.getCurrency());
        payload.put("gross", a.getGrossAmount());
        payload.put("status", a.getStatus().name());
        payload.putAll(extra);
        auditService.log(BidAmendmentService.ENTITY, a.getId(), action, actorId, payload);
    }
}

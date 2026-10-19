package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.supplement.dto.SupplementOptionsResponse;
import com.yadony.api.payments.supplement.dto.SupplementQuoteResponse;
import com.yadony.api.payments.supplement.events.BidSupplementCancelledEvent;
import com.yadony.api.payments.supplement.events.BidSupplementDeclinedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementExpiredEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementWithdrawnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supplément de prix à la remise (plan 2026-10-19) : création, devis, options, retrait, refus,
 * acceptation (déléguée au rail), expiration et fin du colis pour les demandes non payées.
 *
 * <p>Verrous : toujours le colis ({@link BidRepository#lockForUpdate}) puis la ligne du
 * supplément. Le scan DEPART prend le même verrou de colis : une demande et un scan se sérialisent.
 * Aucun appel prestataire ici : les rails ({@link BidSupplementRailHandler}) s'en chargent.
 */
@Service
public class BidSupplementService {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementService.class);

    static final String ENTITY = "BID_SUPPLEMENT";

    /** Colis terminés sans livraison : une demande encore ouverte y est annulée (plan §5.1). */
    public static final Set<BidStatus> TERMINAL_UNDELIVERED = EnumSet.of(BidStatus.CANCELLED, BidStatus.REJECTED,
            BidStatus.EXPIRED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED);

    /**
     * Demandes qui n'ont jamais atteint l'expéditeur (commission espèces refusée, 3DS jamais
     * confirmée, retrait avant la commission) : elles ne comptent pas dans le plafond
     * {@code max-per-bid} et l'expéditeur ne les voit pas.
     */
    public static final Set<String> NEVER_PROPOSED_REASONS = Set.of(BidSupplementCashLedger.COMMISSION_FAILED,
            BidSupplementCashLedger.COMMISSION_NOT_CONFIRMED, "WITHDRAWN_BEFORE_COMMISSION");

    /** Supplément payé, pas encore remboursé : à rendre si le colis finit sans livraison. */
    static final Set<BidSupplementStatus> SETTLING = EnumSet.of(BidSupplementStatus.PAID,
            BidSupplementStatus.REFUND_PENDING, BidSupplementStatus.PAYOUT_PENDING, BidSupplementStatus.PAID_OUT);

    private final BidSupplementRepository supplements;
    private final BidRepository bids;
    private final AnnouncementRepository announcements;
    private final AnnouncementPriceGridItemRepository announcementGridItems;
    private final UserRepository users;
    private final BidSupplementPricing pricing;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;
    private final Map<BidSupplementRail, BidSupplementRailHandler> handlers;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final StorageService storage;
    private final com.yadony.api.disputes.DisputeRepository disputes;

    public BidSupplementService(BidSupplementRepository supplements, BidRepository bids,
                                AnnouncementRepository announcements,
                                AnnouncementPriceGridItemRepository announcementGridItems,
                                UserRepository users, BidSupplementPricing pricing, BidSupplementWindow window,
                                BidSupplementProperties properties, List<BidSupplementRailHandler> handlers,
                                AuditService auditService, ApplicationEventPublisher events, StorageService storage,
                                com.yadony.api.disputes.DisputeRepository disputes) {
        this.supplements = supplements;
        this.bids = bids;
        this.announcements = announcements;
        this.announcementGridItems = announcementGridItems;
        this.users = users;
        this.pricing = pricing;
        this.window = window;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toMap(BidSupplementRailHandler::rail,
                Function.identity(), (a, b) -> a, () -> new java.util.EnumMap<>(BidSupplementRail.class)));
        this.auditService = auditService;
        this.events = events;
        this.storage = storage;
        this.disputes = disputes;
    }

    /** Un composant existe-t-il pour ce rail ? Faux tant que le rail n'est pas livré. */
    public boolean railSupported(BidSupplementRail rail) {
        return handlers.containsKey(rail);
    }

    // ── Voyageur ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SupplementOptionsResponse options(UUID bidId, String firebaseUid) {
        requireEnabled();
        UserEntity traveler = user(firebaseUid);
        BidEntity bid = bid(bidId);
        AnnouncementEntity announcement = announcement(bid);
        requireTraveler(traveler, announcement);
        BidSupplementRail rail = BidSupplementRail.from(bid.getPaymentMethod());
        SupportedCurrency currency = currency(bid);

        List<SupplementOptionsResponse.Suggestion> suggestions = new ArrayList<>();
        announcementGridItems.findByAnnouncementIdOrderByPositionAsc(announcement.getId()).forEach(item ->
                suggestions.add(new SupplementOptionsResponse.Suggestion("GRID_ITEM", item.getLabel(),
                        item.getUnitPriceNet().setScale(currency.minorUnit(), RoundingMode.HALF_UP))));
        BigDecimal perKg = perKgNet(bid, announcement);
        if (perKg != null && perKg.signum() > 0) {
            suggestions.add(new SupplementOptionsResponse.Suggestion("KG", null,
                    perKg.setScale(currency.minorUnit(), RoundingMode.HALF_UP)));
        }
        return new SupplementOptionsResponse(currency.name(), minNet(currency), maxNet(currency),
                List.copyOf(suggestions), rail.name());
    }

    @Transactional(readOnly = true)
    public SupplementQuoteResponse quote(UUID bidId, String firebaseUid, BigDecimal netAmount) {
        requireEnabled();
        UserEntity traveler = user(firebaseUid);
        BidEntity bid = bid(bidId);
        AnnouncementEntity announcement = announcement(bid);
        requireTraveler(traveler, announcement);
        SupportedCurrency currency = currency(bid);
        BidSupplementQuote q = pricing.quote(bid, announcement, requireAmountInRange(netAmount, currency));
        return new SupplementQuoteResponse(q.net(), q.commission(), q.gross(), currency.name());
    }

    @Transactional
    public BidSupplementResponse create(UUID bidId, String firebaseUid, CreateSupplementRequest request) {
        requireEnabled();
        UserEntity traveler = user(firebaseUid);
        bids.lockForUpdate(bidId);
        BidEntity bid = bid(bidId);
        AnnouncementEntity announcement = announcement(bid);
        requireTraveler(traveler, announcement);
        java.time.LocalDateTime expiresAt = window.requireOpen(bid, announcement);

        BidSupplementRail rail = BidSupplementRail.from(bid.getPaymentMethod());
        BidSupplementRailHandler handler = handlers.get(rail);
        if (handler == null) {
            throw BidSupplementRail.unsupported();
        }
        if (!rail.payableBy(traveler, bid.getCurrency())) {
            throw BidSupplementRail.unsupported();
        }

        if (supplements.existsByBidIdAndStatusIn(bidId, BidSupplementStatus.ACTIVE)) {
            throw alreadyActive();
        }
        if (supplements.countTowardLimit(bidId, NEVER_PROPOSED_REASONS) >= properties.getMaxPerBid()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-limit-reached",
                    "Supplement Limit Reached", "Le nombre maximum de demandes de supplément pour ce colis est atteint",
                    Map.of("max", properties.getMaxPerBid()));
        }
        SupportedCurrency currency = currency(bid);
        BigDecimal net = requireAmountInRange(request.netAmount(), currency);
        String photoKey = requirePhotoKey(bidId, request.photoKey());
        BidSupplementQuote q = pricing.quote(bid, announcement, net);

        BidSupplementEntity s = new BidSupplementEntity();
        s.setBidId(bidId);
        s.setTravelerId(traveler.getId());
        s.setSenderId(bid.getSenderId());
        s.setRail(rail);
        s.setStatus(BidSupplementStatus.PROPOSED);
        s.setCurrency(currency.name());
        s.setNetAmount(q.net());
        s.setCommissionAmount(q.commission());
        s.setGrossAmount(q.gross());
        s.setCommissionGridId(q.gridId());
        s.setCommissionRateSnapshot(q.rateSnapshot());
        s.setReasonCode(request.reasonCode());
        s.setReasonText(blankToNull(request.reasonText()));
        s.setGridItemLabel(blankToNull(request.gridItemLabel()));
        s.setPhotoKey(photoKey);
        s.setExpiresAt(expiresAt);
        try {
            s = supplements.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            // Filet de l'index uq_bid_supplements_one_active : la garde ci-dessus lit avant d'écrire.
            // Toute autre contrainte est une vraie erreur, relancée telle quelle.
            String cause = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (cause.contains("uq_bid_supplements_one_active")) {
                throw alreadyActive();
            }
            throw e;
        }
        handler.onCreated(s, request);

        // Espèces payées par carte : la demande attend sa commission (COMMISSION_PENDING) et ne
        // sera annoncée à l'expéditeur qu'une fois la commission prélevée (BidSupplementCashLedger).
        if (s.getStatus() == BidSupplementStatus.PROPOSED) {
            events.publishEvent(new BidSupplementRequestedEvent(s.getId(), bidId, bid.getSenderId(), traveler.getId(),
                    s.getGrossAmount(), s.getCurrency(), s.getExpiresAt(), announcement.getTimezone()));
        }
        audit(s, "BID_SUPPLEMENT_REQUESTED", traveler.getId(), Map.of("reason", s.getReasonCode().name()));
        return BidSupplementViews.of(s, false, storage, window.now());
    }

    @Transactional
    public BidSupplementResponse withdraw(UUID bidId, UUID supplementId, String firebaseUid) {
        UserEntity traveler = user(firebaseUid);
        bids.lockForUpdate(bidId);
        BidSupplementEntity s = supplementForUpdate(bidId, supplementId);
        if (!s.getTravelerId().equals(traveler.getId())) {
            throw forbidden();
        }
        if (s.getStatus() != BidSupplementStatus.PROPOSED && s.getStatus() != BidSupplementStatus.COMMISSION_PENDING) {
            throw notPending();
        }
        // Retirée avant que sa commission ne soit prélevée : l'expéditeur ne l'a jamais vue.
        BidSupplementStatus previous = close(s, BidSupplementStatus.WITHDRAWN,
                s.getStatus() == BidSupplementStatus.COMMISSION_PENDING ? "WITHDRAWN_BEFORE_COMMISSION"
                        : "WITHDRAWN_BY_TRAVELER");
        events.publishEvent(new BidSupplementWithdrawnEvent(s.getId(), bidId, s.getSenderId(), s.getTravelerId(),
                s.getRail(), previous));
        audit(s, "BID_SUPPLEMENT_WITHDRAWN", traveler.getId(), Map.of());
        return BidSupplementViews.of(s, false, storage, window.now());
    }

    // ── Expéditeur ───────────────────────────────────────────────────────────

    @Transactional
    public BidSupplementResponse decline(UUID bidId, UUID supplementId, String firebaseUid) {
        UserEntity sender = user(firebaseUid);
        bids.lockForUpdate(bidId);
        BidSupplementEntity s = supplementForUpdate(bidId, supplementId);
        requireSender(sender, s);
        requireBidInHandover(bidId);
        requireAwaitingSender(s);
        BidSupplementStatus previous = close(s, BidSupplementStatus.DECLINED, "DECLINED_BY_SENDER");
        events.publishEvent(new BidSupplementDeclinedEvent(s.getId(), bidId, s.getSenderId(), s.getTravelerId(),
                s.getRail(), previous));
        audit(s, "BID_SUPPLEMENT_DECLINED", sender.getId(), Map.of());
        return BidSupplementViews.of(s, true, storage, window.now());
    }

    /**
     * Acceptation : déléguée au rail du colis. Sans composant pour ce rail, 422
     * {@code supplement-rail-unsupported} (rails livrés séparément).
     */
    @Transactional
    public SupplementAcceptResponse accept(UUID bidId, UUID supplementId, String firebaseUid) {
        requireEnabled();
        UserEntity sender = user(firebaseUid);
        bids.lockForUpdate(bidId);
        BidSupplementEntity s = supplementForUpdate(bidId, supplementId);
        requireSender(sender, s);
        requireBidInHandover(bidId);
        requireAwaitingSender(s);
        BidSupplementRailHandler handler = handlers.get(s.getRail());
        if (handler == null) {
            throw BidSupplementRail.unsupported();
        }
        return handler.accept(s, sender);
    }

    // ── Fins sans paiement ───────────────────────────────────────────────────

    /**
     * Expire une demande échue encore ouverte (filet planifié, une transaction par demande).
     * {@code COMMISSION_PENDING} (3DS jamais confirmée) est annulée plutôt qu'expirée : rien n'a
     * été proposé à l'expéditeur. Idempotent.
     *
     * @return vrai si la demande a été close.
     */
    @Transactional
    public boolean expire(UUID supplementId) {
        // Colis lu sans charger la demande : elle est relue verrouillée, après le verrou du colis.
        Optional<UUID> foundBid = supplements.findBidIdById(supplementId);
        if (foundBid.isEmpty()) {
            return false;
        }
        UUID bidId = foundBid.get();
        bids.lockForUpdate(bidId);
        BidSupplementEntity s = supplementForUpdate(bidId, supplementId);
        if (!s.getStatus().isOpen() || s.getExpiresAt().isAfter(window.now())) {
            return false;
        }
        BidSupplementRailHandler handler = handlers.get(s.getRail());
        if (s.getStatus() == BidSupplementStatus.PAYMENT_PENDING && handler != null && !handler.mayExpire(s)) {
            return false;
        }
        expireUnderBidLock(s);
        return true;
    }

    /**
     * Clôt une demande échue déjà verrouillée, colis compris (filet, ou porte de la remise sous le
     * verrou du scan). {@code COMMISSION_PENDING} (3DS jamais confirmée) est annulée plutôt
     * qu'expirée : rien n'a été proposé à l'expéditeur.
     */
    public void expireUnderBidLock(BidSupplementEntity s) {
        UUID bidId = s.getBidId();
        if (s.getStatus() == BidSupplementStatus.COMMISSION_PENDING) {
            BidSupplementStatus previous = close(s, BidSupplementStatus.CANCELLED, "COMMISSION_NOT_CONFIRMED");
            events.publishEvent(new BidSupplementCancelledEvent(s.getId(), bidId, s.getSenderId(), s.getTravelerId(),
                    s.getRail(), previous, "COMMISSION_NOT_CONFIRMED"));
            audit(s, "BID_SUPPLEMENT_CANCELLED_COMMISSION_PENDING", null, Map.of());
            return;
        }
        BidSupplementStatus previous = close(s, BidSupplementStatus.EXPIRED, "EXPIRED");
        events.publishEvent(new BidSupplementExpiredEvent(s.getId(), bidId, s.getSenderId(), s.getTravelerId(),
                s.getRail(), previous));
        audit(s, "BID_SUPPLEMENT_EXPIRED", null, Map.of());
    }

    /**
     * Demande espèces restée en {@code COMMISSION_PENDING} depuis avant {@code createdBefore} (3DS
     * de la commission jamais confirmée, plan §4.4 : 30 min) : annulée comme une demande échue,
     * le rail espèces annule ensuite la PaymentIntent de commission. Idempotent.
     *
     * @return vrai si la demande a été annulée.
     */
    @Transactional
    public boolean cancelUnconfirmedCommission(UUID supplementId, java.time.LocalDateTime createdBefore) {
        Optional<UUID> foundBid = supplements.findBidIdById(supplementId);
        if (foundBid.isEmpty()) {
            return false;
        }
        bids.lockForUpdate(foundBid.get());
        BidSupplementEntity s = supplementForUpdate(foundBid.get(), supplementId);
        if (s.getStatus() != BidSupplementStatus.COMMISSION_PENDING || s.getCreatedAt() == null
                || !s.getCreatedAt().isBefore(createdBefore)) {
            return false;
        }
        expireUnderBidLock(s);
        return true;
    }

    /**
     * Le colis s'est terminé sans livraison : une demande encore ouverte est annulée (les rails
     * défont leur part sur {@link BidSupplementCancelledEvent}). Une demande payée est à
     * rembourser : le rail le fait sur {@link BidSupplementRefundRequestedEvent}. N'agit que si le colis est réellement dans
     * {@link #TERMINAL_UNDELIVERED} : un événement mal interprété ne ferme rien. Idempotent.
     *
     * <p>Transaction propre : un colis en échec ne fait pas échouer les autres colis d'un même
     * trajet annulé.
     *
     * @param reason clé du plan §5.1 ({@code BID_REJECTED}, {@code PARCEL_REFUSED}…).
     * @return vrai si une demande a été annulée ou son remboursement demandé.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean onBidEnded(UUID bidId, String reason) {
        if (bidId == null) {
            return false;
        }
        bids.lockForUpdate(bidId);
        BidEntity bid = bids.findById(bidId).orElse(null);
        if (bid == null || !TERMINAL_UNDELIVERED.contains(bid.getStatus())) {
            return false;
        }
        return endUnderBidLock(bid, reason);
    }

    /** {@link #onBidEnded}, colis déjà verrouillé et terminé sans livraison. */
    private boolean endUnderBidLock(BidEntity bid, String reason) {
        UUID bidId = bid.getId();
        Optional<BidSupplementEntity> open = supplements.findByBidIdAndStatusInForUpdate(bidId, BidSupplementStatus.OPEN);
        if (open.isEmpty()) {
            return requestRefundOfPaid(bid, reason);
        }
        BidSupplementEntity s = open.get();
        // Commission espèces jamais prélevée : la demande n'a jamais atteint l'expéditeur, il ne doit
        // pas la voir (même motif que l'échéance d'une 3DS jamais confirmée).
        BidSupplementStatus previous = close(s, BidSupplementStatus.CANCELLED,
                s.getStatus() == BidSupplementStatus.COMMISSION_PENDING
                        ? BidSupplementCashLedger.COMMISSION_NOT_CONFIRMED : reason);
        events.publishEvent(new BidSupplementCancelledEvent(s.getId(), bidId, s.getSenderId(), s.getTravelerId(),
                s.getRail(), previous, reason));
        audit(s, "BID_SUPPLEMENT_CANCELLED_BID_ENDED", null, Map.of("bidStatus", bid.getStatus().name(),
                "endReason", reason));
        log.info("Supplément {} annulé : colis {} terminé ({})", s.getId(), bidId, reason);
        return true;
    }

    /**
     * Colis terminé sans livraison avec un supplément déjà payé (carte, mobile money) : l'argent
     * est rendu à l'expéditeur par le rail, après le commit ({@link BidSupplementRefundRequestedEvent}).
     * {@code PAID → REFUND_PENDING} ; un {@code REFUND_PENDING} est redemandé (reprise) ; un
     * supplément déjà versé ({@code PAYOUT_PENDING}, {@code PAID_OUT}) ne change pas de statut, le
     * rail lève une alerte.
     *
     * <p>Espèces : aucun argent n'est passé par l'app (le complément se remet en main propre, et
     * se rend avec le colis). {@code PAID → REFUNDED} tout de suite, et le rail rend au voyageur la
     * commission du supplément après le commit (même événement). Un {@code REFUNDED} ne revient plus
     * dans le filet des colis terminés.
     */
    private boolean requestRefundOfPaid(BidEntity bid, String reason) {
        Optional<BidSupplementEntity> paid = supplements.findByBidIdAndStatusInForUpdate(bid.getId(), SETTLING);
        if (paid.isEmpty()) {
            return false;
        }
        BidSupplementEntity s = paid.get();
        if (s.getRail() == BidSupplementRail.CASH) {
            if (s.getStatus() != BidSupplementStatus.PAID) {
                return false;
            }
            s.setStatus(BidSupplementStatus.REFUNDED);
            s.setRefundedAt(window.now());
            s.setClosedReason(reason);
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_CASH_CANCELLED_BID_ENDED", null, Map.of("bidStatus", bid.getStatus().name(),
                    "endReason", reason));
            events.publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), bid.getId(), s.getRail(),
                    BidSupplementStatus.PAID, reason));
            log.info("Supplément espèces {} clos : colis {} terminé ({}), commission à rendre", s.getId(),
                    bid.getId(), reason);
            return true;
        }
        if (s.getStatus() == BidSupplementStatus.PAID) {
            s.setStatus(BidSupplementStatus.REFUND_PENDING);
            s.setClosedReason(reason);
            supplements.save(s);
            audit(s, "BID_SUPPLEMENT_REFUND_REQUESTED", null, Map.of("bidStatus", bid.getStatus().name(),
                    "endReason", reason));
        }
        events.publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), bid.getId(), s.getRail(),
                s.getStatus(), reason));
        log.info("Supplément {} à rembourser : colis {} terminé ({})", s.getId(), bid.getId(), reason);
        return true;
    }

    /**
     * Paiement principal remboursé par l'administration ({@code AdminPaymentRefundedEvent}) :
     * <ul>
     *   <li>colis terminé sans livraison : même règle que {@link #onBidEnded} ;</li>
     *   <li>colis encore en cours ou livré : un supplément payé (carte, mobile money) est
     *       remboursé aussi, l'expéditeur ne paie pas un supplément d'un transport remboursé ;</li>
     *   <li>litige ouvert sur le colis : rien, le supplément reste payé (décision manuelle).</li>
     * </ul>
     * Idempotent.
     *
     * @return vrai si une demande a été annulée ou son remboursement demandé.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean onPrincipalRefunded(UUID bidId) {
        if (bidId == null) {
            return false;
        }
        bids.lockForUpdate(bidId);
        BidEntity bid = bids.findById(bidId).orElse(null);
        if (bid == null) {
            return false;
        }
        if (TERMINAL_UNDELIVERED.contains(bid.getStatus())) {
            return endUnderBidLock(bid, "ADMIN_REFUND");
        }
        if (disputes.existsByBidIdAndStatusAndTypeStartingWith(bidId, com.yadony.api.disputes.DisputeTypes.STATUS_OPEN,
                "")) {
            log.info("Principal du colis {} remboursé, litige ouvert : supplément laissé payé (décision manuelle)",
                    bidId);
            return false;
        }
        return requestRefundOfPaid(bid, "ADMIN_REFUND");
    }

    // ── Règles ───────────────────────────────────────────────────────────────

    private BidSupplementStatus close(BidSupplementEntity s, BidSupplementStatus to, String reason) {
        BidSupplementStatus previous = s.getStatus();
        s.setStatus(to);
        s.setRespondedAt(window.now());
        s.setClosedReason(reason);
        supplements.save(s);
        return previous;
    }

    /**
     * Relu sous le verrou du colis : une réponse à un colis terminé (annulé, refusé…) n'a plus de
     * sens, la fin du colis ferme la demande ({@link #onBidEnded}).
     */
    private void requireBidInHandover(UUID bidId) {
        BidStatus status = bid(bidId).getStatus();
        if (status != BidStatus.ACCEPTED && status != BidStatus.HANDED_OVER) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status",
                    "Unprocessable", "Ce colis n'attend plus de réponse à un supplément");
        }
    }

    private void requireAwaitingSender(BidSupplementEntity s) {
        if (s.getStatus() != BidSupplementStatus.PROPOSED && s.getStatus() != BidSupplementStatus.PAYMENT_PENDING) {
            throw notPending();
        }
        if (!s.getExpiresAt().isAfter(window.now())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-expired", "Supplement Expired",
                    "Le délai de réponse à cette demande de supplément est dépassé");
        }
    }

    private BigDecimal requireAmountInRange(BigDecimal amount, SupportedCurrency currency) {
        BigDecimal min = minNet(currency);
        BigDecimal max = maxNet(currency);
        BigDecimal net = amount == null ? null : amount.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        if (net == null || net.compareTo(min) < 0 || net.compareTo(max) > 0) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("min", min);
            props.put("max", max);
            props.put("currency", currency.name());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-amount-out-of-range",
                    "Supplement Amount Out Of Range", "Le montant du supplément est hors des limites autorisées", props);
        }
        return net;
    }

    private BigDecimal minNet(SupportedCurrency currency) {
        return CurrencyBounds.scaleFromEur(properties.getMinNetEur(), currency);
    }

    private BigDecimal maxNet(SupportedCurrency currency) {
        return CurrencyBounds.scaleFromEur(properties.getMaxNetEur(), currency);
    }

    /** Même règle que le scan ({@code TrackingService.validatedPhotoKey}) : une clé R2 du colis, jamais une URL. */
    private static String requirePhotoKey(UUID bidId, String photoKey) {
        String prefix = "tracking/" + bidId + "/";
        if (photoKey == null || photoKey.contains("..") || !photoKey.startsWith(prefix)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-photo-url",
                    "Invalid Photo URL", "La photo doit être une clé de stockage valide pour ce colis");
        }
        return photoKey;
    }

    /** Prix d'un kilo de plus pour le voyageur : celui de l'accord négocié s'il existe, sinon du trajet. */
    private static BigDecimal perKgNet(BidEntity bid, AnnouncementEntity announcement) {
        if (bid.getNegotiatedNetEur() != null && bid.getWeightKg() != null && bid.getWeightKg().signum() > 0) {
            return bid.getNegotiatedNetEur().divide(bid.getWeightKg(), 2, RoundingMode.HALF_UP);
        }
        return announcement.getPricePerKg();
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-disabled",
                    "Supplement Disabled", "La demande de supplément n'est pas disponible");
        }
    }

    private static void requireTraveler(UserEntity user, AnnouncementEntity announcement) {
        if (!user.getId().equals(announcement.getTravelerId())) {
            throw forbidden();
        }
    }

    private static void requireSender(UserEntity user, BidSupplementEntity s) {
        if (!user.getId().equals(s.getSenderId())) {
            throw forbidden();
        }
    }

    private UserEntity user(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private BidEntity bid(UUID bidId) {
        return bids.findById(bidId).orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "bid-not-found", "Bid Not Found", "Transaction introuvable"));
    }

    private AnnouncementEntity announcement(BidEntity bid) {
        return announcements.findById(bid.getAnnouncementId()).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
    }

    private BidSupplementEntity supplementForUpdate(UUID bidId, UUID supplementId) {
        return supplements.findByIdAndBidIdForUpdate(supplementId, bidId).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "supplement-not-found", "Supplement Not Found", "Demande de supplément introuvable"));
    }

    private static SupportedCurrency currency(BidEntity bid) {
        return SupportedCurrency.fromCodeOrDefault(bid.getCurrency());
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
        auditService.log(ENTITY, s.getId(), action, actorId, payload);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static YadonyBusinessException forbidden() {
        return new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "Vous n'êtes pas partie à ce colis");
    }

    private static YadonyBusinessException notPending() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-not-pending", "Supplement Not Pending",
                "Cette demande de supplément n'attend plus de réponse");
    }

    private static YadonyBusinessException alreadyActive() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-already-active",
                "Supplement Already Active", "Une demande de supplément est déjà en cours pour ce colis");
    }
}

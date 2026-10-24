package com.yadony.api.payments.amendment;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.PriceGridService;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.AmendmentLineRequest;
import com.yadony.api.payments.amendment.dto.AmendmentOptionsResponse;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteRequest;
import com.yadony.api.payments.amendment.dto.AmendmentQuoteResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.amendment.events.BidAmendmentAcceptedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRequestedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Modification d'un colis accepté (plan 2026-10-23) : options, devis, création, annulation par
 * l'expéditeur, refus et acceptation par le voyageur (déléguée au rail), expiration.
 *
 * <p>Verrous, toujours dans cet ordre : le colis ({@link BidRepository#lockForUpdate}), puis le trajet
 * ({@link AnnouncementRepository#findByIdForUpdate}), puis la modification. Le colis sérialise les
 * demandes d'un même expéditeur et le scan de remise ; le trajet sérialise la réservation des derniers
 * kilos entre colis différents. L'éligibilité est refaite sous ces verrous ({@link BidExtrasNet} n'est
 * cohérent que sous celui du colis). Aucun appel prestataire ici : les rails ({@link BidAmendmentRailHandler})
 * s'en chargent après le commit.
 */
@Service
public class BidAmendmentService {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentService.class);

    static final String ENTITY = "BID_AMENDMENT";

    /** L'expéditeur peut retirer sa demande tant que le voyageur ne l'a pas acquise (ni commission en cours). */
    private static final Set<BidAmendmentStatus> CANCELLABLE_BY_SENDER = EnumSet.of(
            BidAmendmentStatus.AUTHORIZATION_PENDING, BidAmendmentStatus.PROPOSED, BidAmendmentStatus.PAYMENT_PENDING);

    /** Étapes d'où un rail peut rendre la modification acquise. */
    private static final Set<BidAmendmentStatus> ACCEPTABLE = EnumSet.of(
            BidAmendmentStatus.PROPOSED, BidAmendmentStatus.COMMISSION_PENDING, BidAmendmentStatus.PAYMENT_PENDING);

    private final BidAmendmentRepository amendments;
    private final BidAmendmentItemRepository amendmentItems;
    private final BidRepository bids;
    private final AnnouncementRepository announcements;
    private final AnnouncementPriceGridItemRepository announcementGridItems;
    private final UserRepository users;
    private final BidAmendmentEligibility eligibility;
    private final BidAmendmentPricing pricing;
    private final BidAmendmentWindow window;
    private final BidAmendmentCapacity capacity;
    private final BidAmendmentProperties properties;
    private final PriceGridService priceGrid;
    private final ObjectProvider<BidAmendmentRailHandler> handlers;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public BidAmendmentService(BidAmendmentRepository amendments, BidAmendmentItemRepository amendmentItems,
                               BidRepository bids, AnnouncementRepository announcements,
                               AnnouncementPriceGridItemRepository announcementGridItems, UserRepository users,
                               BidAmendmentEligibility eligibility, BidAmendmentPricing pricing,
                               BidAmendmentWindow window, BidAmendmentCapacity capacity,
                               BidAmendmentProperties properties, PriceGridService priceGrid,
                               ObjectProvider<BidAmendmentRailHandler> handlers, AuditService auditService,
                               ApplicationEventPublisher events) {
        this.amendments = amendments;
        this.amendmentItems = amendmentItems;
        this.bids = bids;
        this.announcements = announcements;
        this.announcementGridItems = announcementGridItems;
        this.users = users;
        this.eligibility = eligibility;
        this.pricing = pricing;
        this.window = window;
        this.capacity = capacity;
        this.properties = properties;
        this.priceGrid = priceGrid;
        // Résolus à l'usage : les rails dépendent du service ({@link #markAccepted}).
        this.handlers = handlers;
        this.auditService = auditService;
        this.events = events;
    }

    // ── Expéditeur ───────────────────────────────────────────────────────────

    /**
     * Ce que l'expéditeur peut ajouter : kilos libres ({@code null} en capacité libre), prix au kilo et prix
     * des articles tels que la recherche les lui montre ({@link PriceGridService#displayPrice}), moyens de
     * paiement proposables ET livrés, échéance de réponse, bornes du net.
     */
    @Transactional(readOnly = true)
    public AmendmentOptionsResponse options(UUID bidId, String firebaseUid) {
        UserEntity sender = user(firebaseUid);
        BidEntity bid = bid(bidId);
        AnnouncementEntity announcement = announcements.findById(bid.getAnnouncementId())
                .orElseThrow(BidAmendmentService::announcementNotFound);
        UserEntity traveler = traveler(announcement);
        requireEligible(bid, announcement, traveler, sender, null);
        List<String> methods = availableRails(bid, announcement, traveler).stream().map(Enum::name).toList();
        if (methods.isEmpty()) {
            throw BidAmendmentEligibility.exceptionFor("amendment-rail-unavailable");
        }

        SupportedCurrency currency = currency(bid);
        UUID travelerId = announcement.getTravelerId();
        List<AmendmentOptionsResponse.GridItemOption> gridItems =
                announcementGridItems.findByAnnouncementIdOrderByPositionAsc(announcement.getId()).stream()
                        .map(item -> new AmendmentOptionsResponse.GridItemOption(item.getId(), item.getLabel(),
                                priceGrid.displayPrice(item.getUnitPriceNet(), travelerId)))
                        .toList();
        return new AmendmentOptionsResponse(
                currency.name(),
                announcement.getCapacityUnit() == CapacityUnit.KG_FREE ? null : announcement.getAvailableKg(),
                announcement.getPricePerKg() == null ? null
                        : priceGrid.displayPrice(announcement.getPricePerKg(), travelerId),
                gridItems,
                methods,
                BidAmendmentViews.utc(window.deadline(bid, announcement).orElse(null)),
                minNet(currency),
                maxNet(currency));
    }

    /**
     * Brut d'une modification, sans rien réserver. Refuse ce que la création refuserait (kilos indisponibles,
     * montant hors bornes) : l'app ne présente jamais un devis impossible.
     */
    @Transactional(readOnly = true)
    public AmendmentQuoteResponse quote(UUID bidId, String firebaseUid, AmendmentQuoteRequest request) {
        UserEntity sender = user(firebaseUid);
        BidEntity bid = bid(bidId);
        AnnouncementEntity announcement = announcements.findById(bid.getAnnouncementId())
                .orElseThrow(BidAmendmentService::announcementNotFound);
        BigDecimal addedKg = normalizeKg(request.addedKg());
        requireEligible(bid, announcement, traveler(announcement), sender, addedKg);
        BidAmendmentQuote q = price(bid, announcement, addedKg, request.items());
        return new AmendmentQuoteResponse(q.gross(), currency(bid).name());
    }

    /**
     * Crée la modification, réserve ses kilos et la remet au rail choisi. Sous les verrous du colis puis du
     * trajet : l'éligibilité, la capacité et le prix sont ceux du moment de la création.
     *
     * @throws YadonyBusinessException 422 {@code amendment-rail-unavailable} (moyen non proposé ou rail non
     *         livré), 409 {@code amendment-capacity-unavailable}, 422 {@code amendment-amount-out-of-range},
     *         409 {@code amendment-already-open}, ou tout refus d'éligibilité.
     */
    @Transactional
    public AmendmentCreateResponse create(UUID bidId, String firebaseUid, CreateAmendmentRequest request) {
        UserEntity sender = user(firebaseUid);
        lockBid(bidId);
        BidEntity bid = bid(bidId);
        AnnouncementEntity announcement = lockAnnouncement(bid).orElseThrow(BidAmendmentService::announcementNotFound);
        UserEntity traveler = traveler(announcement);
        BigDecimal addedKg = normalizeKg(request.addedKg());
        requireEligible(bid, announcement, traveler, sender, addedKg);

        PaymentMethod method = request.paymentMethod();
        BidAmendmentRailHandler handler = method == null
                || !eligibility.offerableRails(bid, announcement, traveler).contains(method)
                ? null : handler(BidSupplementRail.from(method)).orElse(null);
        if (handler == null) {
            throw BidAmendmentEligibility.exceptionFor("amendment-rail-unavailable");
        }
        BidAmendmentQuote q = price(bid, announcement, addedKg, request.items());
        LocalDateTime expiresAt = window.deadline(bid, announcement)
                .orElseThrow(() -> BidAmendmentEligibility.exceptionFor("amendment-not-eligible"));

        BidAmendmentEntity a = new BidAmendmentEntity();
        a.setBidId(bidId);
        a.setAnnouncementId(announcement.getId());
        a.setTravelerId(announcement.getTravelerId());
        a.setSenderId(bid.getSenderId());
        a.setRail(handler.rail());
        a.setCurrency(currency(bid).name());
        a.setAddedWeightKg(addedKg);
        a.setNetAmount(q.net());
        a.setCommissionAmount(q.commission());
        a.setGrossAmount(q.gross());
        a.setCommissionGridId(q.gridId());
        a.setCommissionRateSnapshot(q.rateSnapshot());
        a.setExpiresAt(expiresAt);
        try {
            a = amendments.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // Filet de l'index uq_bid_amendments_one_open : l'éligibilité lit avant d'écrire.
            String cause = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            if (cause.contains("uq_bid_amendments_one_open")) {
                throw BidAmendmentEligibility.exceptionFor("amendment-already-open");
            }
            throw e;
        }
        capacity.reserve(a, announcement);
        List<BidAmendmentItemEntity> items = saveItems(a, q);
        a.setStatus(handler.onCreated(a, sender).status());
        a = amendments.save(a);

        // Carte : la demande attend l'autorisation du montant (AUTHORIZATION_PENDING) ; le voyageur ne la
        // verra qu'une fois le montant bloqué.
        if (a.getStatus() == BidAmendmentStatus.PROPOSED) {
            events.publishEvent(new BidAmendmentRequestedEvent(a.getId(), bidId, a.getSenderId(), a.getTravelerId(),
                    a.getGrossAmount(), a.getNetAmount(), a.getCurrency(), a.getExpiresAt(),
                    announcement.getTimezone()));
        }
        audit(a, "BID_AMENDMENT_REQUESTED", sender.getId(), Map.of("itemCount", items.size()));
        return AmendmentCreateResponse.of(BidAmendmentViews.of(a, items, true, window));
    }

    /**
     * L'expéditeur retire sa demande avant qu'elle ne soit acquise. 409 {@code amendment-payment-in-progress}
     * si un dépôt mobile money est en vol ({@link BidAmendmentRailHandler#mayExpire} faux).
     */
    @Transactional
    public BidAmendmentResponse cancel(UUID bidId, UUID amendmentId, String firebaseUid) {
        requireEnabled();
        UserEntity sender = user(firebaseUid);
        BidAmendmentEntity a = lockForResponse(bidId, amendmentId);
        if (!sender.getId().equals(a.getSenderId())) {
            throw forbidden();
        }
        if (!CANCELLABLE_BY_SENDER.contains(a.getStatus())) {
            throw notPending();
        }
        Optional<BidAmendmentRailHandler> handler = handler(a.getRail());
        if (a.getStatus() == BidAmendmentStatus.PAYMENT_PENDING && handler.isPresent()
                && !handler.get().mayExpire(a)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-payment-in-progress",
                    "Amendment Payment In Progress", "Un paiement de cette modification est en cours de traitement");
        }
        close(a, BidAmendmentStatus.CANCELLED, BidAmendmentClosedReason.CANCELLED_BY_SENDER, sender.getId());
        return view(a, true);
    }

    // ── Voyageur ─────────────────────────────────────────────────────────────

    @Transactional
    public BidAmendmentResponse decline(UUID bidId, UUID amendmentId, String firebaseUid) {
        requireEnabled();
        UserEntity traveler = user(firebaseUid);
        BidAmendmentEntity a = lockForResponse(bidId, amendmentId);
        requireAwaitingTraveler(a, traveler);
        a.setRespondedAt(window.now());
        close(a, BidAmendmentStatus.DECLINED, BidAmendmentClosedReason.DECLINED_BY_TRAVELER, traveler.getId());
        return view(a, false);
    }

    /** Acceptation : déléguée au rail de la modification, sous les verrous. */
    @Transactional
    public AmendmentAcceptResponse accept(UUID bidId, UUID amendmentId, String firebaseUid,
                                          AmendmentAcceptRequest request) {
        requireEnabled();
        UserEntity traveler = user(firebaseUid);
        BidAmendmentEntity a = lockForResponse(bidId, amendmentId);
        requireAwaitingTraveler(a, traveler);
        if (bid(bidId).getStatus() != BidStatus.ACCEPTED) {
            throw BidAmendmentEligibility.exceptionFor("invalid-bid-status");
        }
        BidAmendmentRailHandler handler = handler(a.getRail())
                .orElseThrow(() -> BidAmendmentEligibility.exceptionFor("amendment-rail-unavailable"));
        return handler.accept(a, traveler, request);
    }

    /**
     * Rend la modification acquise ({@code ACCEPTED}) : appelé par les rails, sous les verrous du colis et de
     * la modification. Idempotent pour une modification déjà acquise (rejeu d'un webhook).
     *
     * @throws IllegalStateException si elle n'est ni proposée ni en attente de commission ou de paiement.
     */
    public void markAccepted(BidAmendmentEntity a) {
        if (a.getStatus().isSecured()) {
            return;
        }
        if (!ACCEPTABLE.contains(a.getStatus())) {
            throw new IllegalStateException("Modification " + a.getId() + " non acceptable depuis " + a.getStatus());
        }
        LocalDateTime now = window.now();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setAcceptedAt(now);
        a.setRespondedAt(now);
        amendments.save(a);
        events.publishEvent(new BidAmendmentAcceptedEvent(a.getId(), a.getBidId(), a.getSenderId(),
                a.getTravelerId(), a.getRail(), false));
        audit(a, "BID_AMENDMENT_ACCEPTED", a.getTravelerId(), Map.of());
    }

    /**
     * Clôt une modification ouverte parce que son colis n'est plus actif ({@code BID_ENDED}) : annulée, kilos
     * rendus, rail prévenu, événement publié. Appelé sous les verrous du colis, du trajet et de la modification.
     *
     * @return vrai si la modification était ouverte et a été close.
     */
    public boolean cancelOpen(BidAmendmentEntity a, BidAmendmentClosedReason reason) {
        if (!a.getStatus().isOpen()) {
            return false;
        }
        close(a, BidAmendmentStatus.CANCELLED, reason, null);
        return true;
    }

    // ── Expiration ───────────────────────────────────────────────────────────

    /**
     * Clôt une modification dont l'étape en cours est échue ({@link BidAmendmentWindow#openUntil}) : filet
     * planifié, une transaction par modification. Idempotent.
     *
     * @return vrai si la modification a été close.
     */
    @Transactional
    public boolean expire(UUID amendmentId) {
        Optional<UUID> foundBid = amendments.findBidIdById(amendmentId);
        if (foundBid.isEmpty() || bids.lockForUpdate(foundBid.get()) == 0) {
            return false;
        }
        UUID bidId = foundBid.get();
        bids.findById(bidId).ifPresent(this::lockAnnouncement);
        return amendments.findByIdAndBidIdForUpdate(amendmentId, bidId)
                .map(this::expireUnderBidLock)
                .orElse(false);
    }

    /**
     * {@link #expire}, colis et modification déjà verrouillés (filet, ou portail de la remise sous le verrou
     * du scan). Rien si l'étape en cours n'est pas échue, ou si un dépôt mobile money est en vol.
     * <ul>
     *   <li>{@code AUTHORIZATION_PENDING} : {@code CANCELLED} ({@code AUTHORIZATION_NOT_CONFIRMED}) ;</li>
     *   <li>{@code PROPOSED} : {@code EXPIRED} ({@code EXPIRED}) ;</li>
     *   <li>{@code PAYMENT_PENDING} : {@code EXPIRED} ({@code PAYMENT_NOT_RECEIVED}) si le rail le permet ;</li>
     *   <li>{@code COMMISSION_PENDING} : {@code CANCELLED} ({@code COMMISSION_NOT_CONFIRMED}).</li>
     * </ul>
     * Kilos rendus, rail prévenu, événement publié dans tous les cas.
     *
     * @return vrai si la modification a été close.
     */
    public boolean expireUnderBidLock(BidAmendmentEntity a) {
        if (!a.getStatus().isOpen() || !window.isLapsed(a)) {
            return false;
        }
        switch (a.getStatus()) {
            case AUTHORIZATION_PENDING -> close(a, BidAmendmentStatus.CANCELLED,
                    BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED, null);
            case PROPOSED -> close(a, BidAmendmentStatus.EXPIRED, BidAmendmentClosedReason.EXPIRED, null);
            case PAYMENT_PENDING -> {
                Optional<BidAmendmentRailHandler> handler = handler(a.getRail());
                if (handler.isPresent() && !handler.get().mayExpire(a)) {
                    return false;
                }
                close(a, BidAmendmentStatus.EXPIRED, BidAmendmentClosedReason.PAYMENT_NOT_RECEIVED, null);
            }
            case COMMISSION_PENDING -> close(a, BidAmendmentStatus.CANCELLED,
                    BidAmendmentClosedReason.COMMISSION_NOT_CONFIRMED, null);
            default -> {
                return false;
            }
        }
        log.info("Modification {} du colis {} close à échéance ({})", a.getId(), a.getBidId(), a.getClosedReason());
        return true;
    }

    // ── Règles ───────────────────────────────────────────────────────────────

    /**
     * Ferme une modification ouverte : statut et motif, kilos rendus, rail prévenu, événement et audit. Les
     * kilos sont rendus avant l'événement : ses écouteurs voient la capacité à jour.
     */
    private void close(BidAmendmentEntity a, BidAmendmentStatus to, BidAmendmentClosedReason reason, UUID actorId) {
        BidAmendmentStatus previous = a.getStatus();
        a.setStatus(to);
        a.setClosedReason(reason);
        amendments.save(a);
        capacity.release(a);
        handler(a.getRail()).ifPresent(h -> h.onClosed(a, previous));
        events.publishEvent(switch (to) {
            case DECLINED -> new BidAmendmentClosedEvent.Declined(a.getId(), a.getBidId(), a.getSenderId(),
                    a.getTravelerId(), a.getRail(), previous, reason);
            case EXPIRED -> new BidAmendmentClosedEvent.Expired(a.getId(), a.getBidId(), a.getSenderId(),
                    a.getTravelerId(), a.getRail(), previous, reason);
            case CANCELLED -> new BidAmendmentClosedEvent.Cancelled(a.getId(), a.getBidId(), a.getSenderId(),
                    a.getTravelerId(), a.getRail(), previous, reason);
            default -> throw new IllegalArgumentException("Clôture vers " + to + " non prévue");
        });
        audit(a, "BID_AMENDMENT_" + to.name(), actorId,
                Map.of("previousStatus", previous.name(), "closedReason", reason.name()));
    }

    /** Verrous colis, trajet, modification, dans cet ordre, pour répondre à une modification. */
    private BidAmendmentEntity lockForResponse(UUID bidId, UUID amendmentId) {
        lockBid(bidId);
        lockAnnouncement(bid(bidId));
        return amendments.findByIdAndBidIdForUpdate(amendmentId, bidId).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "amendment-not-found", "Amendment Not Found", "Modification introuvable"));
    }

    private void requireAwaitingTraveler(BidAmendmentEntity a, UserEntity traveler) {
        if (!traveler.getId().equals(a.getTravelerId())) {
            throw forbidden();
        }
        if (a.getStatus() != BidAmendmentStatus.PROPOSED) {
            throw notPending();
        }
        if (!a.getExpiresAt().isAfter(window.now())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-expired", "Amendment Expired",
                    "Le délai de réponse à cette modification est dépassé");
        }
    }

    /**
     * Refus d'éligibilité, s'il y en a un. Quand {@code addedKg} dépasse les kilos libres, la règle « rien à
     * ajouter » est ignorée : la capacité ({@link #price}) répond alors 409 {@code amendment-capacity-unavailable},
     * même sur un trajet sans grille dont le dernier kilo vient d'être pris.
     */
    private void requireEligible(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler,
                                 UserEntity sender, BigDecimal addedKg) {
        boolean exceedsCapacity = addedKg != null && !BidAmendmentCapacity.isAvailable(announcement, addedKg);
        eligibility.blockingReason(bid, announcement, traveler, sender.getId(), exceedsCapacity).ifPresent(code -> {
            throw BidAmendmentEligibility.exceptionFor(code);
        });
    }

    /** Devis, kilos disponibles et bornes du net : ce que la création et le devis refusent à l'identique. */
    private BidAmendmentQuote price(BidEntity bid, AnnouncementEntity announcement, BigDecimal addedKg,
                                    List<AmendmentLineRequest> items) {
        BidAmendmentCapacity.requireAvailable(announcement, addedKg);
        BidAmendmentQuote q = pricing.quote(bid, announcement, addedKg, items);
        SupportedCurrency currency = currency(bid);
        BigDecimal min = minNet(currency);
        BigDecimal max = maxNet(currency);
        if (q.net().compareTo(min) < 0 || q.net().compareTo(max) > 0) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("min", min);
            props.put("max", max);
            props.put("currency", currency.name());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "amendment-amount-out-of-range",
                    "Amendment Amount Out Of Range", "Le montant de la modification est hors des limites autorisées",
                    props);
        }
        return q;
    }

    /** Rails proposables au voyageur ET livrés (un composant existe). */
    private Set<PaymentMethod> availableRails(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler) {
        Set<PaymentMethod> rails = EnumSet.noneOf(PaymentMethod.class);
        for (PaymentMethod method : eligibility.offerableRails(bid, announcement, traveler)) {
            if (handler(BidSupplementRail.from(method)).isPresent()) {
                rails.add(method);
            }
        }
        return rails;
    }

    private List<BidAmendmentItemEntity> saveItems(BidAmendmentEntity a, BidAmendmentQuote q) {
        return q.lines().stream().map(line -> {
            BidAmendmentItemEntity item = new BidAmendmentItemEntity();
            item.setAmendmentId(a.getId());
            item.setAnnouncementGridItemId(line.gridItemId());
            item.setLabelSnapshot(line.label());
            item.setUnitPriceNetSnapshot(line.unitNet());
            item.setQuantity(line.quantity());
            return amendmentItems.save(item);
        }).toList();
    }

    private Optional<BidAmendmentRailHandler> handler(BidSupplementRail rail) {
        return handlers.orderedStream().filter(h -> h.rail() == rail).findFirst();
    }

    private BidAmendmentResponse view(BidAmendmentEntity a, boolean forSender) {
        return BidAmendmentViews.of(a, amendmentItems.findByAmendmentId(a.getId()), forSender, window);
    }

    private BigDecimal minNet(SupportedCurrency currency) {
        return CurrencyBounds.scaleFromEur(properties.getMinNetEur(), currency);
    }

    private BigDecimal maxNet(SupportedCurrency currency) {
        return CurrencyBounds.scaleFromEur(properties.getMaxNetEur(), currency);
    }

    /** Kilos à la précision de la colonne (2 décimales), 0 si absents. */
    private static BigDecimal normalizeKg(BigDecimal addedKg) {
        return addedKg == null ? BigDecimal.ZERO.setScale(2) : addedKg.setScale(2, RoundingMode.HALF_UP);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw BidAmendmentEligibility.exceptionFor("amendment-disabled");
        }
    }

    private void lockBid(UUID bidId) {
        if (bids.lockForUpdate(bidId) == 0) {
            throw bidNotFound();
        }
    }

    /** Verrou du trajet du colis : toujours APRÈS celui du colis, AVANT toute lecture du trajet. */
    private Optional<AnnouncementEntity> lockAnnouncement(BidEntity bid) {
        return announcements.findByIdForUpdate(bid.getAnnouncementId());
    }

    private UserEntity user(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private UserEntity traveler(AnnouncementEntity announcement) {
        return users.findById(announcement.getTravelerId()).orElse(null);
    }

    private BidEntity bid(UUID bidId) {
        return bids.findById(bidId).orElseThrow(BidAmendmentService::bidNotFound);
    }

    private static SupportedCurrency currency(BidEntity bid) {
        return SupportedCurrency.fromCodeOrDefault(bid.getCurrency());
    }

    /**
     * Une entrée par transition : colis, modification, rail, brut et kilos. Ni net ni commission : le journal
     * reste lisible sans révéler à quiconque le net du voyageur.
     */
    private void audit(BidAmendmentEntity a, String action, UUID actorId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", a.getBidId().toString());
        payload.put("amendmentId", a.getId().toString());
        payload.put("rail", a.getRail().name());
        payload.put("currency", a.getCurrency());
        payload.put("gross", a.getGrossAmount());
        payload.put("addedWeightKg", a.getAddedWeightKg());
        payload.put("status", a.getStatus().name());
        payload.putAll(extra);
        auditService.log(ENTITY, a.getId(), action, actorId, payload);
    }

    private static YadonyBusinessException bidNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                "Transaction introuvable");
    }

    private static YadonyBusinessException announcementNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                "Announcement Not Found", "Annonce introuvable");
    }

    private static YadonyBusinessException forbidden() {
        return new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "Vous n'êtes pas partie à cette modification");
    }

    private static YadonyBusinessException notPending() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-not-pending", "Amendment Not Pending",
                "Cette modification n'attend plus de réponse");
    }
}

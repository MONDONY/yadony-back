package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidCustomItemRequest;
import com.yadony.api.matching.dto.BidCustomItemResponse;
import com.yadony.api.matching.dto.BidGridItemLine;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationMessageResponse;
import com.yadony.api.matching.dto.BidNegotiationResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moteur des tours de négociation d'un trajet, porté par le {@link BidEntity} lui-même.
 *
 * <p>Un fil vit en {@link BidStatus#NEGOTIATING} : ce n'est PAS une réservation, il
 * reste invisible de toutes les listes de colis (cf. {@code BidNegotiationLeakTest}).
 * À l'accord, le bid bascule en {@link BidStatus#AWAITING_PAYMENT} et rejoint le flux
 * de paiement existant sans autre adaptation.
 *
 * <p>Trois invariants gouvernent ce service :
 * <ul>
 *   <li><b>Tour de parole</b> : l'auteur de la dernière proposition ne peut ni
 *       contre-proposer ni accepter la sienne.</li>
 *   <li><b>Taux figé</b> : le taux de commission est résolu UNE SEULE fois, dans
 *       {@link #propose}, et stocké sur le bid. {@link #accept} relit ce snapshot ;
 *       le résolveur n'est jamais rappelé, sinon un changement de barème entre la
 *       proposition et l'accord ferait payer autre chose que le prix négocié.</li>
 *   <li><b>Vue par rôle</b> : le voyageur reçoit son net, l'expéditeur le brut et sa
 *       commission. Le net du voyageur ne quitte jamais le serveur vers l'expéditeur.</li>
 * </ul>
 */
@Service
public class BidNegotiationService {

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BidNegotiationMessageRepository messageRepository;
    private final BidCustomItemRepository customItemRepository;
    private final BidGridItemRepository bidGridItemRepository;
    private final AnnouncementPriceGridItemRepository annGridItemRepository;
    private final BidPhotoService bidPhotoService;
    private final CommissionRateResolver commissionRateResolver;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final MatchingNegotiationConfig config;
    private final BidService bidService;

    public BidNegotiationService(BidRepository bidRepository,
                                 AnnouncementRepository announcementRepository,
                                 UserRepository userRepository,
                                 BidNegotiationMessageRepository messageRepository,
                                 BidCustomItemRepository customItemRepository,
                                 BidGridItemRepository bidGridItemRepository,
                                 AnnouncementPriceGridItemRepository annGridItemRepository,
                                 BidPhotoService bidPhotoService,
                                 CommissionRateResolver commissionRateResolver,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher,
                                 MatchingNegotiationConfig config,
                                 BidService bidService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.customItemRepository = customItemRepository;
        this.bidGridItemRepository = bidGridItemRepository;
        this.annGridItemRepository = annGridItemRepository;
        this.bidPhotoService = bidPhotoService;
        this.commissionRateResolver = commissionRateResolver;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.bidService = bidService;
    }

    // ── Ouverture du fil ─────────────────────────────────────────────────────

    @Transactional
    public BidNegotiationResponse propose(UUID announcementId, String firebaseUid,
                                          BidNegotiationStartRequest request,
                                          HttpServletRequest httpRequest) {
        UserEntity sender = findUser(firebaseUid);
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.isNegotiable()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "announcement-not-negotiable", "Announcement Not Negotiable",
                    "Ce trajet n'accepte pas les propositions de prix");
        }
        assertTripNotDeparted(announcement);

        // Gardes partagées avec l'offre ferme : jamais dupliquées ici.
        String normalizedCategory =
                bidService.assertCanBidOn(sender, announcement, request.contentCategory());

        if (Boolean.FALSE.equals(request.disclaimerSigned())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "disclaimer-not-signed", "Disclaimer Not Signed",
                    "Le disclaimer légal doit être accepté");
        }

        List<BidGridItemRequest> gridItems =
                request.gridItems() != null ? request.gridItems() : List.of();
        List<BidCustomItemRequest> customItems =
                request.customItems() != null ? request.customItems() : List.of();
        boolean hasKg = request.weightKg() != null && request.weightKg().compareTo(BigDecimal.ZERO) > 0;
        boolean hasItems = !gridItems.isEmpty() || !customItems.isEmpty();

        if (!hasKg && !hasItems) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-empty", "Bid Empty",
                    "Au moins un article ou un poids doit être renseigné");
        }

        if (announcement.getCapacityUnit() != CapacityUnit.KG_FREE
                && request.weightKg() != null
                && announcement.getAvailableKg() != null
                && request.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "weight-exceeds-capacity", "Weight Exceeds Capacity",
                    "Poids demandé supérieur à la capacité disponible");
        }

        PaymentMethod paymentMethod =
                bidService.resolvePaymentMethodFor(announcement, request.paymentMethod());

        // Taux figé ICI et nulle part ailleurs — accept() relira ce snapshot.
        BigDecimal rate = commissionRateResolver.resolve(announcement.getTravelerId(), sender.getId());

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(request.weightKg());
        bid.setPricingMode(hasItems && hasKg ? BidPricingMode.MIXED
                         : hasItems          ? BidPricingMode.GRID
                         :                     BidPricingMode.KG);
        bid.setDescription(request.description());
        bid.setContentCategory(normalizedCategory);
        bid.setRecipientName(request.recipientName());
        bid.setRecipientPhone(request.recipientPhone());
        bid.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        bid.setDisclaimerSignedIp(bidService.resolveClientIp(httpRequest));
        if (paymentMethod != null) {
            bid.setPaymentMethod(paymentMethod);
        }
        bid.setStatus(BidStatus.NEGOTIATING);
        bid.setCurrency(announcement.getCurrency());
        bid.setCommissionRate(rate);
        bid.setNegotiationRound(1);

        BidEntity saved = bidRepository.save(bid);

        persistGridItems(saved, announcement, gridItems);
        persistCustomItems(saved, customItems);
        bidPhotoService.attachPhotos(saved.getId(), sender.getId(), request.photoKeys());

        BidNegotiationMessageEntity message = postMessage(saved, sender.getId(),
                BidNegotiationMessageKind.PROPOSAL, request.proposedTotalEur(), null);

        auditService.log("BID", saved.getId(), "BID_NEGOTIATION_PROPOSED", sender.getId(),
                Map.of("announcementId", announcementId.toString(),
                        "proposedGrossEur", request.proposedTotalEur().toPlainString(),
                        "round", "1"));

        publishPosted(saved, announcement, sender.getId(), BidNegotiationMessageKind.PROPOSAL,
                request.proposedTotalEur());

        return buildResponse(saved, announcement, sender.getId(), message);
    }

    // ── Rounds ───────────────────────────────────────────────────────────────

    @Transactional
    public BidNegotiationResponse counter(UUID bidId, String firebaseUid,
                                          BidNegotiationCounterRequest request) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        assertThreadOpen(ctx);

        BidNegotiationMessageEntity last = lastMessage(bidId);
        assertMyTurn(last, ctx.userId());

        if (ctx.bid().getNegotiationRound() >= config.maxRounds()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-round-limit-reached", "Negotiation Round Limit Reached",
                    "Le nombre maximum de propositions échangées est atteint");
        }

        ctx.bid().setNegotiationRound(ctx.bid().getNegotiationRound() + 1);
        BidEntity saved = bidRepository.save(ctx.bid());

        BidNegotiationMessageEntity message = postMessage(ctx.bid(), ctx.userId(),
                BidNegotiationMessageKind.COUNTER, request.proposedTotalEur(), request.body());

        auditService.log("BID", bidId, "BID_NEGOTIATION_COUNTERED", ctx.userId(),
                Map.of("proposedGrossEur", request.proposedTotalEur().toPlainString(),
                        "round", String.valueOf(ctx.bid().getNegotiationRound())));

        publishPosted(ctx.bid(), ctx.announcement(), ctx.userId(),
                BidNegotiationMessageKind.COUNTER, request.proposedTotalEur());

        return buildResponse(saved != null ? saved : ctx.bid(), ctx.announcement(), ctx.userId(), message);
    }

    @Transactional
    public BidNegotiationResponse accept(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        assertThreadOpen(ctx);

        BidNegotiationMessageEntity last = lastMessage(bidId);
        assertMyTurn(last, ctx.userId());

        BigDecimal gross = last != null ? last.getProposedGrossEur() : null;
        if (gross == null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-closed", "Negotiation Closed",
                    "Aucune proposition de prix à accepter sur ce fil");
        }

        // Le taux vient du bid, jamais d'une nouvelle résolution : le prix accepté
        // doit être exactement celui affiché au moment de la proposition.
        BigDecimal rate = ctx.bid().getCommissionRate() != null
                ? ctx.bid().getCommissionRate() : BigDecimal.ZERO;
        BidNegotiationPricing.Split split = BidNegotiationPricing.split(gross, rate);

        ctx.bid().setNegotiatedGrossEur(split.grossEur());
        ctx.bid().setNegotiatedNetEur(split.netEur());
        ctx.bid().setStatus(BidStatus.AWAITING_PAYMENT);
        ctx.bid().setAwaitingPaymentExpiresAt(
                LocalDateTime.now(ZoneOffset.UTC).plusHours(config.awaitingPaymentHours()));
        BidEntity saved = bidRepository.save(ctx.bid());

        BidNegotiationMessageEntity message = postMessage(ctx.bid(), ctx.userId(),
                BidNegotiationMessageKind.ACCEPT, split.grossEur(), null);

        auditService.log("BID", bidId, "BID_NEGOTIATION_ACCEPTED", ctx.userId(),
                Map.of("grossEur", split.grossEur().toPlainString(),
                        "netEur", split.netEur().toPlainString(),
                        "commissionEur", split.commissionEur().toPlainString(),
                        "round", String.valueOf(ctx.bid().getNegotiationRound())));

        publishPosted(ctx.bid(), ctx.announcement(), ctx.userId(),
                BidNegotiationMessageKind.ACCEPT, split.grossEur());

        return buildResponse(saved != null ? saved : ctx.bid(), ctx.announcement(), ctx.userId(), message);
    }

    @Transactional
    public BidNegotiationResponse reject(UUID bidId, String firebaseUid) {
        return closeThread(bidId, firebaseUid, BidStatus.REJECTED, "BID_NEGOTIATION_REJECTED");
    }

    @Transactional
    public BidNegotiationResponse cancel(UUID bidId, String firebaseUid) {
        return closeThread(bidId, firebaseUid, BidStatus.CANCELLED, "BID_NEGOTIATION_CANCELLED");
    }

    private BidNegotiationResponse closeThread(UUID bidId, String firebaseUid,
                                               BidStatus finalStatus, String auditAction) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        assertThreadOpen(ctx);

        ctx.bid().setStatus(finalStatus);
        BidEntity saved = bidRepository.save(ctx.bid());

        BidNegotiationMessageEntity message = postMessage(ctx.bid(), ctx.userId(),
                BidNegotiationMessageKind.REJECT, null, null);

        auditService.log("BID", bidId, auditAction, ctx.userId(),
                Map.of("finalStatus", finalStatus.name(),
                        "round", String.valueOf(ctx.bid().getNegotiationRound())));

        publishPosted(ctx.bid(), ctx.announcement(), ctx.userId(),
                BidNegotiationMessageKind.REJECT, null);

        return buildResponse(saved != null ? saved : ctx.bid(), ctx.announcement(), ctx.userId(), message);
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BidNegotiationResponse thread(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        return buildResponse(ctx.bid(), ctx.announcement(), ctx.userId(), lastMessage(bidId));
    }

    @Transactional
    public void markRead(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (ctx.viewerIsTraveler()) {
            ctx.bid().setTravelerLastReadAt(now);
        } else {
            ctx.bid().setSenderLastReadAt(now);
        }
        bidRepository.save(ctx.bid());
    }

    @Transactional(readOnly = true)
    public List<BidNegotiationSummaryResponse> myNegotiations(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        List<BidNegotiationSummaryResponse> rows = new ArrayList<>();
        for (BidEntity bid : bidRepository.findNegotiationsForUser(user.getId())) {
            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElse(null);
            if (announcement == null) {
                continue;
            }
            boolean viewerIsTraveler = announcement.getTravelerId().equals(user.getId());
            BidNegotiationMessageEntity last = lastMessage(bid.getId());
            LocalDateTime lastReadAt = viewerIsTraveler
                    ? bid.getTravelerLastReadAt() : bid.getSenderLastReadAt();

            rows.add(new BidNegotiationSummaryResponse(
                    bid.getId(),
                    announcement.getId(),
                    bid.getStatus().name(),
                    bid.getNegotiationRound(),
                    isMyTurn(last, user.getId()),
                    hasUnread(last, user.getId(), lastReadAt),
                    currentGross(bid, last),
                    bid.getCurrency(),
                    displayName(viewerIsTraveler ? bid.getSenderId() : announcement.getTravelerId()),
                    announcement.getDepartureCity(),
                    announcement.getArrivalCity(),
                    announcement.getDepartureDate(),
                    bid.getUpdatedAt(),
                    viewerIsTraveler ? "TRAVELER" : "SENDER"));
        }
        return rows;
    }

    // ── Gardes et helpers ────────────────────────────────────────────────────

    /** Contexte d'une action : le fil, son trajet, et le rôle de l'appelant. */
    private record Participant(BidEntity bid, AnnouncementEntity announcement,
                               UUID userId, boolean viewerIsTraveler) {}

    private Participant loadParticipant(UUID bidId, String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        boolean isSender = bid.getSenderId().equals(user.getId());
        boolean isTraveler = announcement.getTravelerId().equals(user.getId());
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous ne participez pas à cette discussion");
        }
        return new Participant(bid, announcement, user.getId(), isTraveler);
    }

    private void assertThreadOpen(Participant ctx) {
        if (ctx.bid().getStatus() != BidStatus.NEGOTIATING) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-closed", "Negotiation Closed",
                    "Cette discussion de prix est terminée");
        }
        assertTripNotDeparted(ctx.announcement());
    }

    /**
     * Une fois le trajet parti, plus aucune action n'a de sens : le colis ne peut
     * plus embarquer. Le fil restant est éteint par
     * {@code BidNegotiationExpiryScheduler}, mais l'API doit refuser tout de suite.
     */
    private void assertTripNotDeparted(AnnouncementEntity announcement) {
        LocalDate departure = announcement.getDepartureDate();
        if (departure != null && departure.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-closed", "Negotiation Closed",
                    "Ce trajet est déjà parti");
        }
    }

    private void assertMyTurn(BidNegotiationMessageEntity last, UUID callerId) {
        if (!isMyTurn(last, callerId)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "not-your-turn", "Not Your Turn",
                    "C'est à la contrepartie de répondre");
        }
    }

    private boolean isMyTurn(BidNegotiationMessageEntity last, UUID callerId) {
        return last == null || !callerId.equals(last.getAuthorId());
    }

    private boolean hasUnread(BidNegotiationMessageEntity last, UUID viewerId, LocalDateTime lastReadAt) {
        if (last == null || viewerId.equals(last.getAuthorId())) {
            return false;
        }
        return lastReadAt == null || last.getCreatedAt() == null || lastReadAt.isBefore(last.getCreatedAt());
    }

    private BidNegotiationMessageEntity lastMessage(UUID bidId) {
        return messageRepository.findFirstByBidIdOrderByCreatedAtDesc(bidId).orElse(null);
    }

    private BidNegotiationMessageEntity postMessage(BidEntity bid, UUID authorId,
                                                    BidNegotiationMessageKind kind,
                                                    BigDecimal proposedGrossEur, String body) {
        BidNegotiationMessageEntity message = BidNegotiationMessageEntity.create(
                bid.getId(), authorId, kind, proposedGrossEur, body);
        BidNegotiationMessageEntity saved = messageRepository.save(message);
        return saved != null ? saved : message;
    }

    private void publishPosted(BidEntity bid, AnnouncementEntity announcement, UUID authorId,
                               BidNegotiationMessageKind kind, BigDecimal proposedGrossEur) {
        UUID recipientId = authorId.equals(bid.getSenderId())
                ? announcement.getTravelerId() : bid.getSenderId();
        eventPublisher.publishEvent(new BidNegotiationMessagePostedEvent(
                bid.getId(), announcement.getId(), authorId, recipientId,
                kind, proposedGrossEur, bid.getNegotiationRound()));
    }

    private void persistGridItems(BidEntity bid, AnnouncementEntity announcement,
                                  List<BidGridItemRequest> gridItems) {
        if (gridItems.isEmpty()) {
            return;
        }
        List<BidGridItemEntity> entities = new ArrayList<>();
        for (BidGridItemRequest req : gridItems) {
            AnnouncementPriceGridItemEntity annItem = annGridItemRepository
                    .findById(req.announcementGridItemId())
                    .filter(i -> i.getAnnouncementId().equals(announcement.getId()))
                    .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "invalid-grid-item", "Invalid Grid Item",
                            "Article hors grille de cette annonce : " + req.announcementGridItemId()));
            BidGridItemEntity entity = new BidGridItemEntity();
            entity.setBidId(bid.getId());
            entity.setAnnouncementGridItemId(req.announcementGridItemId());
            entity.setLabelSnapshot(annItem.getLabel());
            entity.setUnitPriceNetSnapshot(annItem.getUnitPriceNet());
            entity.setQuantity(req.quantity());
            entities.add(entity);
        }
        bidGridItemRepository.saveAll(entities);
    }

    private void persistCustomItems(BidEntity bid, List<BidCustomItemRequest> customItems) {
        if (customItems.isEmpty()) {
            return;
        }
        List<BidCustomItemEntity> entities = new ArrayList<>();
        for (BidCustomItemRequest req : customItems) {
            BidCustomItemEntity entity = new BidCustomItemEntity();
            entity.setBidId(bid.getId());
            entity.setLabel(req.label());
            entity.setQuantity(req.quantity());
            entity.setAmountEur(req.amountEur());
            entities.add(entity);
        }
        customItemRepository.saveAll(entities);
    }

    private BigDecimal currentGross(BidEntity bid, BidNegotiationMessageEntity last) {
        if (last != null && last.getProposedGrossEur() != null) {
            return last.getProposedGrossEur();
        }
        return bid.getNegotiatedGrossEur();
    }

    private String displayName(UUID userId) {
        return userRepository.findById(userId)
                .map(UserEntity::publicDisplayName)
                .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
    }

    // ── Construction de la réponse ───────────────────────────────────────────

    /**
     * @param last dernier message du fil — passé explicitement plutôt que relu, pour
     *             que la réponse d'une mutation reflète le message qui vient d'être
     *             posté sans dépendre de l'ordre de flush.
     */
    private BidNegotiationResponse buildResponse(BidEntity bid, AnnouncementEntity announcement,
                                                 UUID viewerId, BidNegotiationMessageEntity last) {
        boolean viewerIsTraveler = announcement.getTravelerId().equals(viewerId);
        BigDecimal rate = bid.getCommissionRate() != null ? bid.getCommissionRate() : BigDecimal.ZERO;
        BigDecimal gross = currentGross(bid, last);

        BigDecimal netEur = null;
        BigDecimal commissionEur = null;
        if (gross != null && gross.compareTo(BigDecimal.ZERO) > 0) {
            BidNegotiationPricing.Split split = BidNegotiationPricing.split(gross, rate);
            // Le net du voyageur ne descend jamais vers l'expéditeur, et
            // réciproquement la commission n'intéresse que celui qui la paie.
            netEur = viewerIsTraveler ? split.netEur() : null;
            commissionEur = viewerIsTraveler ? null : split.commissionEur();
        }

        List<BidGridItemEntity> gridEntities = bidGridItemRepository.findByBidId(bid.getId());
        List<BidGridItemLine> gridLines = gridEntities.stream()
                .map(e -> new BidGridItemLine(e.getId(), e.getLabelSnapshot(),
                        grossUp(e.getUnitPriceNetSnapshot(), rate), e.getQuantity()))
                .toList();

        List<BidCustomItemResponse> customLines =
                customItemRepository.findByBidIdOrderByCreatedAtAsc(bid.getId()).stream()
                        .map(e -> new BidCustomItemResponse(e.getId(), e.getLabel(),
                                e.getQuantity(), e.getAmountEur()))
                        .toList();

        List<BidNegotiationMessageResponse> messages =
                messageRepository.findByBidIdOrderByCreatedAtAsc(bid.getId()).stream()
                        .map(m -> new BidNegotiationMessageResponse(m.getId(), m.getKind().name(),
                                m.getAuthorId(), m.getProposedGrossEur(), m.getBody(), m.getCreatedAt()))
                        .toList();

        List<String> photoUrls = bidPhotoService.activePhotos(bid.getId()).stream()
                .map(p -> p.url()).toList();

        boolean open = bid.getStatus() == BidStatus.NEGOTIATING;
        boolean myTurn = open && isMyTurn(last, viewerId);

        return new BidNegotiationResponse(
                bid.getId(),
                announcement.getId(),
                bid.getStatus().name(),
                bid.getNegotiationRound(),
                config.maxRounds(),
                myTurn,
                myTurn && bid.getNegotiationRound() < config.maxRounds(),
                bid.getCurrency(),
                gross,
                netEur,
                commissionEur,
                suggestedGross(bid, announcement, gridEntities, rate),
                bid.getWeightKg(),
                bid.getDescription(),
                bid.getContentCategory(),
                gridLines,
                customLines,
                photoUrls,
                displayName(viewerIsTraveler ? bid.getSenderId() : announcement.getTravelerId()),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                announcement.getDepartureDate(),
                expiresAt(bid, last, open),
                messages);
    }

    /**
     * Échéance d'inactivité, comptée depuis le dernier message et non depuis la
     * dernière écriture sur le bid : c'est exactement le critère du scheduler, et une
     * simple lecture du fil ne doit pas repousser sa date d'expiration.
     */
    private LocalDateTime expiresAt(BidEntity bid, BidNegotiationMessageEntity last, boolean open) {
        if (!open) {
            return null;
        }
        LocalDateTime reference = last != null && last.getCreatedAt() != null
                ? last.getCreatedAt() : bid.getUpdatedAt();
        return reference != null ? reference.plusHours(config.inactivityHours()) : null;
    }

    /**
     * Total « au barème » du voyageur, brut : ce que coûterait le même colis sans
     * négociation. Sert de repère à l'écran. Les articles hors grille en sont exclus,
     * le voyageur ne les ayant jamais tarifés.
     */
    private BigDecimal suggestedGross(BidEntity bid, AnnouncementEntity announcement,
                                      List<BidGridItemEntity> gridEntities, BigDecimal rate) {
        BigDecimal net = BigDecimal.ZERO;
        for (BidGridItemEntity item : gridEntities) {
            net = net.add(item.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (bid.getWeightKg() != null && announcement.getPricePerKg() != null) {
            net = net.add(announcement.getPricePerKg().multiply(bid.getWeightKg()));
        }
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return grossUp(net, rate);
    }

    private BigDecimal grossUp(BigDecimal net, BigDecimal rate) {
        return net.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    private UserEntity findUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }
}

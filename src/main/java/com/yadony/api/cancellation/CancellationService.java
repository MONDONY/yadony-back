package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.CancellationRequest;
import com.yadony.api.cancellation.dto.CancellationResponse;
import com.yadony.api.cancellation.dto.RematchSuggestionDto;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.DeliveryNoShowReportedEvent;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.cancellation.dto.ReturnCodeResponse;
import com.yadony.api.cancellation.events.ParcelReturnedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.cancellation.events.TravelerNoShowReportedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import java.security.SecureRandom;
import com.yadony.api.payments.cash.CommissionProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CancellationService {

    private final CancellationRepository cancellationRepository;
    private final RematchSuggestionRepository rematchSuggestionRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final CommissionProperties commissionProperties;
    private final RematchService rematchService;
    private final StorageService storageService;

    private static final SecureRandom RETURN_CODE_RANDOM = new SecureRandom();
    private static final int MAX_RETURN_CODE_ATTEMPTS = 3;

    public CancellationService(CancellationRepository cancellationRepository,
                                RematchSuggestionRepository rematchSuggestionRepository,
                                BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                UserRepository userRepository,
                                AuditService auditService,
                                ApplicationEventPublisher eventPublisher,
                                CommissionProperties commissionProperties,
                                RematchService rematchService,
                                StorageService storageService) {
        this.cancellationRepository = cancellationRepository;
        this.rematchSuggestionRepository = rematchSuggestionRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.commissionProperties = commissionProperties;
        this.rematchService = rematchService;
        this.storageService = storageService;
    }

    @Transactional
    public CancellationResponse cancelTrip(String firebaseUid, CancellationRequest request) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        AnnouncementEntity announcement = announcementRepository.findById(request.announcementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à annuler cette annonce");
        }

        if (announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-cancelled", "Already Cancelled",
                    "Ce trajet est déjà annulé");
        }
        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "Seul un trajet ACTIVE peut être annulé");
        }

        // Cancel the announcement
        announcement.setStatus(AnnouncementStatus.CANCELLED);
        announcementRepository.save(announcement);

        List<CancellationEntity> cancellations =
                cancelOpenBidsAndPublish(announcement, traveler.getId(), request.reason());

        // Track cancellation count on traveler profile for reputation penalty
        traveler.setCancellationCount(traveler.getCancellationCount() + 1);
        userRepository.save(traveler);

        int cancellationCount = traveler.getCancellationCount();

        auditService.log("ANNOUNCEMENT", request.announcementId(), "TRIP_CANCELLED", traveler.getId(),
                Map.of("reason", request.reason(),
                       "affectedBids", String.valueOf(cancellations.size()),
                       "cancellationCount", String.valueOf(cancellationCount)));

        if (cancellationCount >= 3) {
            auditService.log("USER", traveler.getId(), "HIGH_CANCELLATION_ALERT", traveler.getId(),
                    Map.of("cancellationCount", String.valueOf(cancellationCount),
                           "triggeringAnnouncementId", request.announcementId().toString()));
            eventPublisher.publishEvent(new TravelerHighCancellationEvent(
                    traveler.getId(), cancellationCount, request.announcementId()));
        }

        // La réponse HTTP continue de renvoyer les suggestions du PREMIER expéditeur affecté
        // (comportement historique, consommé par l'écran voyageur post-annulation) ; les
        // autres expéditeurs récupèrent les leurs via GET /cancellations/{id}/rematch.
        List<RematchSuggestionDto> suggestions = cancellations.isEmpty()
                ? List.of()
                : buildRematchSuggestionDtos(cancellations.get(0).getId());

        return new CancellationResponse(
                request.announcementId(),
                cancellations.size(),
                request.reason(),
                suggestions,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    /**
     * Annulation système d'un trajet suite à la suppression du compte du voyageur
     * (cf. {@code auth.AccountFinalizationService} → {@code AccountDeletionRequestedEvent}
     * → {@code AccountDeletionCancellationListener}). Même cœur que {@link #cancelTrip}
     * (bids ouverts annulés + CancellationEntity par bid + rematch + TripCancelledEvent, donc
     * refund/notif/rematch pour chaque sender affecté) mais SANS vérification
     * ownership/firebaseUid (acteur système) et SANS pénalité de réputation (le compte est de
     * toute façon banni). Contrairement à {@code cancelTrip}, cancelle aussi FULL — pas
     * seulement ACTIVE — puisqu'il n'y a plus personne pour rouvrir la capacité derrière.
     * Idempotent : no-op si l'annonce n'existe plus ou n'est déjà plus ACTIVE/FULL.
     */
    @Transactional
    public void cancelAnnouncementForDeletedTraveler(UUID announcementId) {
        AnnouncementEntity announcement = announcementRepository.findById(announcementId).orElse(null);
        if (announcement == null
                || (announcement.getStatus() != AnnouncementStatus.ACTIVE
                    && announcement.getStatus() != AnnouncementStatus.FULL)) {
            return;
        }

        announcement.setStatus(AnnouncementStatus.CANCELLED);
        announcementRepository.save(announcement);

        List<CancellationEntity> cancellations = cancelOpenBidsAndPublish(
                announcement, announcement.getTravelerId(), CancellationReason.TRAVELER_ACCOUNT_DELETED.name());

        auditService.log("ANNOUNCEMENT", announcementId, "TRIP_CANCELLED", announcement.getTravelerId(),
                Map.of("reason", CancellationReason.TRAVELER_ACCOUNT_DELETED.name(),
                       "affectedBids", String.valueOf(cancellations.size())));
    }

    /**
     * Cœur commun à {@link #cancelTrip} et {@link #cancelAnnouncementForDeletedTraveler} :
     * annule les bids ouverts (PENDING/PAYMENT_ESCROWED/ACCEPTED) de l'annonce, crée une
     * CancellationEntity par bid, génère le rematch, publie {@link TripCancelledEvent} (déclenche
     * refund + notification + rematch côté listeners). L'annonce elle-même doit déjà avoir été
     * basculée à CANCELLED par l'appelant.
     */
    private List<CancellationEntity> cancelOpenBidsAndPublish(
            AnnouncementEntity announcement, UUID actorId, String reason) {
        // Cancel ALL in-progress bids on this trip (not just ACCEPTED) so each
        // sender's bid reflects the cancelled trip — sinon un bid PENDING /
        // PAYMENT_ESCROWED gardait son statut partout. Set « actif » canonique
        // (cf. BidCheckoutService). Les remboursements sont gérés par les
        // listeners de TripCancelledEvent, par bid, selon le statut du PAIEMENT :
        // un PAYMENT_ESCROWED (escrow détenu) est remboursé, un PENDING cash
        // (sans paiement) est simplement annulé.
        List<BidEntity> affectedBids = bidRepository.findByAnnouncementIdAndStatusIn(
                announcement.getId(),
                List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED));

        List<UUID> affectedSenderIds = new ArrayList<>();
        List<UUID> affectedBidIds = new ArrayList<>();
        List<CancellationEntity> cancellations = new ArrayList<>();

        for (BidEntity bid : affectedBids) {
            bid.setStatus(BidStatus.CANCELLED);
            bidRepository.save(bid);

            CancellationEntity cancellation = new CancellationEntity();
            cancellation.setBidId(bid.getId());
            cancellation.setCancelledBy(actorId);
            cancellation.setReason(reason);
            cancellations.add(cancellationRepository.save(cancellation));

            affectedSenderIds.add(bid.getSenderId());
            affectedBidIds.add(bid.getId());
        }

        // Build bidPaymentMethods so listeners (e.g. WalletCancellationListener) don't need BidRepository
        Map<UUID, String> bidPaymentMethods = new HashMap<>();
        Map<UUID, String> bidCommissionChargedVia = new HashMap<>();
        for (BidEntity bid : affectedBids) {
            String methodName = bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE";
            bidPaymentMethods.put(bid.getId(), methodName);
            if (bid.getCommissionChargedVia() != null) {
                bidCommissionChargedVia.put(bid.getId(), bid.getCommissionChargedVia().name());
            }
        }

        // Generate rematch suggestions for each affected sender's cancellation (one
        // RematchService call per bid, capacity-filtered per sender — fix du bug qui ne
        // générait des suggestions que pour le 1er expéditeur affecté).
        Map<UUID, RematchService.RematchInfo> rematchBySender =
                rematchService.generateForCancellations(announcement, affectedBids, cancellations);

        // Publish event for notifications (Epic 8) and payment refunds (Story 6.7) — après
        // la génération des suggestions rematch. rematchInfo permet à NotificationDispatcher
        // de rendre la notification TRIP_CANCELLED conditionnelle (deep link si suggestions).
        Map<UUID, TripCancelledEvent.RematchBySenderInfo> rematchInfo = new HashMap<>();
        rematchBySender.forEach((senderId, info) -> rematchInfo.put(senderId,
                new TripCancelledEvent.RematchBySenderInfo(info.cancellationId(), info.suggestionCount())));

        eventPublisher.publishEvent(new TripCancelledEvent(
                announcement.getId(), announcement.getTravelerId(), affectedSenderIds, reason,
                affectedBidIds, bidPaymentMethods, bidCommissionChargedVia, rematchInfo));

        return cancellations;
    }

    @Transactional(readOnly = true)
    public List<RematchSuggestionDto> getRematchSuggestions(UUID cancellationId, String firebaseUid) {
        CancellationEntity cancellation = cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "cancellation-not-found", "Not Found", "Annulation introuvable"));

        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));

        BidEntity bid = bidRepository.findById(cancellation.getBidId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        boolean isParticipant = caller.getId().equals(bid.getSenderId())
                || caller.getId().equals(announcement.getTravelerId())
                || caller.getId().equals(cancellation.getCancelledBy());
        if (!isParticipant) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "not-participant", "Forbidden",
                    "Vous n'êtes pas concerné par cette annulation");
        }

        return buildRematchSuggestionDtos(cancellationId);
    }

    /** Mappe les {@link RematchSuggestionEntity} persistées d'une cancellation vers leurs DTOs
     *  (résolution de l'annonce alternative associée + infos voyageur). Réutilisé par
     *  {@code cancelTrip} (suggestions du 1er expéditeur affecté) et
     *  {@code getRematchSuggestions} (consultation par n'importe quel expéditeur affecté via
     *  son propre cancellationId). Annonces alternatives ET voyageurs sont chacun batch-chargés
     *  (un seul {@code findAllById} par type) — pas de {@code findById} par suggestion. */
    private List<RematchSuggestionDto> buildRematchSuggestionDtos(UUID cancellationId) {
        List<RematchSuggestionEntity> suggestionEntities =
                rematchSuggestionRepository.findByCancellationId(cancellationId);
        if (suggestionEntities.isEmpty()) {
            return List.of();
        }

        List<UUID> announcementIds = suggestionEntities.stream()
                .map(RematchSuggestionEntity::getAnnouncementId)
                .distinct()
                .toList();
        Map<UUID, AnnouncementEntity> announcementsById = announcementRepository.findAllById(announcementIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(AnnouncementEntity::getId, a -> a));

        List<UUID> travelerIds = announcementsById.values().stream()
                .map(AnnouncementEntity::getTravelerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, UserEntity> travelersById = travelerIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(travelerIds).stream()
                        .collect(java.util.stream.Collectors.toMap(UserEntity::getId, u -> u));

        return suggestionEntities.stream()
                .map(s -> {
                    AnnouncementEntity a = announcementsById.get(s.getAnnouncementId());
                    if (a == null) return null;
                    UserEntity traveler = a.getTravelerId() != null
                            ? travelersById.get(a.getTravelerId())
                            : null;
                    String travelerFirstName = traveler != null ? traveler.getFirstName() : null;
                    java.math.BigDecimal travelerRating = traveler != null ? traveler.getAverageRating() : null;
                    Integer travelerRatingCount = traveler != null ? traveler.getRatingCount() : null;
                    String travelerAvatarUrl = traveler != null
                            ? storageService.avatarUrl(traveler.getAvatarUrl())
                            : null;
                    return new RematchSuggestionDto(s.getId(), a.getId(),
                            a.getDepartureCity(), a.getArrivalCity(),
                            a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg(),
                            travelerFirstName, travelerRating, travelerRatingCount,
                            travelerAvatarUrl);
                })
                .filter(s -> s != null)
                .toList();
    }

    @Transactional
    public CancellationEntity reportSenderNoShow(UUID bidId, UUID travelerId) {
        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new IllegalStateException("Le bid doit être en statut ACCEPTED.");
        }
        LocalDateTime handoverEnd = bid.getHandoverDeadline();
        if (handoverEnd == null || LocalDateTime.now().isBefore(handoverEnd)) {
            throw new IllegalStateException("Vous ne pouvez signaler qu'après l'heure de remise prévue.");
        }
        if (cancellationRepository.existsByBidIdAndNoShowStatusIn(bidId,
                List.of(CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONFIRMED))) {
            throw new IllegalStateException("Une annulation est déjà en cours pour ce bid.");
        }
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(travelerId);
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(
                OffsetDateTime.now().plusHours(commissionProperties.noShowContestationHours()));
        return cancellationRepository.save(c);
    }

    /**
     * L'expéditeur signale un voyageur absent (no-show manuel). Vérifie l'ownership, le statut
     * ACCEPTED et que la date limite de dépôt est passée, puis publie
     * {@link TravelerNoShowReportedEvent} — écouté côté matching/ par {@code NoShowService}.
     */
    @Transactional
    public void reportTravelerNoShow(UUID bidId, UUID senderId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce bid.");
        }
        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new IllegalStateException("Le bid doit être en statut ACCEPTED.");
        }
        LocalDateTime handoverEnd = bid.getHandoverDeadline();
        if (handoverEnd == null || LocalDateTime.now().isBefore(handoverEnd)) {
            throw new IllegalStateException("Vous ne pouvez signaler qu'après l'heure de remise prévue.");
        }
        auditService.log("BID", bidId, "TRAVELER_NO_SHOW_REPORTED", senderId,
                Map.of("bidId", bidId.toString()));
        eventPublisher.publishEvent(new TravelerNoShowReportedEvent(bidId, senderId));
    }

    /** Le voyageur signale que le destinataire ne s'est pas présenté à la remise (arrivée). */
    @Transactional
    public CancellationEntity reportDeliveryNoShow(UUID bidId, UUID travelerId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        AnnouncementEntity announcement = assertDeliveryReportable(bid);
        if (!announcement.getTravelerId().equals(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas le voyageur de ce bid.");
        }

        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(travelerId);
        c.setReason(DeliveryNoShowTypes.REASON_RECIPIENT_NO_SHOW);
        c.setScope(CancellationScope.DELIVERY);
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(
                OffsetDateTime.now().plusHours(commissionProperties.noShowContestationHours()));
        CancellationEntity saved = cancellationRepository.save(c);

        auditService.log("BID", bidId, "DELIVERY_NOSHOW_REPORTED_BY_TRAVELER", travelerId,
                Map.of("bidId", bidId.toString()));
        eventPublisher.publishEvent(new DeliveryNoShowReportedEvent(
                bidId, bid.getSenderId(), travelerId, true));

        return saved;
    }

    /** L'expéditeur signale que le voyageur ne livre pas / est injoignable à l'arrivée. */
    @Transactional
    public CancellationEntity reportTravelerDeliveryNoShow(UUID bidId, UUID senderId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce bid.");
        }
        AnnouncementEntity announcement = assertDeliveryReportable(bid);

        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(senderId);
        c.setReason(DeliveryNoShowTypes.REASON_TRAVELER_DELIVERY_NO_SHOW);
        c.setScope(CancellationScope.DELIVERY);
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(
                OffsetDateTime.now().plusHours(commissionProperties.noShowContestationHours()));
        CancellationEntity saved = cancellationRepository.save(c);

        auditService.log("BID", bidId, "DELIVERY_NOSHOW_REPORTED_BY_SENDER", senderId,
                Map.of("bidId", bidId.toString()));
        eventPublisher.publishEvent(new DeliveryNoShowReportedEvent(
                bidId, senderId, announcement.getTravelerId(), false));

        return saved;
    }

    /** Garde commune aux deux signalements de livraison : bid IN_TRANSIT, trajet déjà
     *  parti, aucun signalement DELIVERY déjà en cours ou contesté sur ce bid.
     *  Retourne l'annonce chargée pour éviter un second fetch chez l'appelant (D8 :
     *  sert notamment à vérifier que l'appelant est bien le voyageur assigné). */
    private AnnouncementEntity assertDeliveryReportable(BidEntity bid) {
        if (bid.getStatus() != BidStatus.IN_TRANSIT) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "bid-not-in-transit", "Invalid Status",
                    "Le bid doit être en statut IN_TRANSIT.");
        }
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));
        if (announcement.getDepartureDate() == null
                || !announcement.getDepartureDate().isBefore(LocalDate.now().plusDays(1))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "trip-not-departed", "Invalid Status",
                    "Le trajet n'est pas encore parti.");
        }
        if (cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(bid.getId(), CancellationScope.DELIVERY,
                List.of(CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONTESTED))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "delivery-noshow-in-progress",
                    "Already In Progress",
                    "Un signalement d'absence à la livraison est déjà en cours pour ce bid.");
        }
        return announcement;
    }

    /** La partie adverse à celle qui a signalé conteste, avant la deadline.
     *  `reason` du signalement détermine le type de litige ouvert :
     *  RECIPIENT_NO_SHOW → contesté par le sender → RECIPIENT_NO_SHOW_CONTESTED ;
     *  TRAVELER_DELIVERY_NO_SHOW → contesté par le traveler → TRAVELER_DELIVERY_NO_SHOW_CONTESTED. */
    @Transactional
    public void contestDeliveryNoShow(UUID bidId, UUID callerId) {
        CancellationEntity c = cancellationRepository.findByBidIdAndScope(bidId, CancellationScope.DELIVERY)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "cancellation-not-found", "Not Found",
                        "Aucun signalement d'absence à la livraison pour ce bid"));
        if (c.getContestationDeadline() == null
                || OffsetDateTime.now().isAfter(c.getContestationDeadline())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "contestation-deadline-passed",
                    "Contestation Deadline Passed", "Le délai de contestation est dépassé.");
        }

        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElseThrow();

        boolean isRecipientNoShow = DeliveryNoShowTypes.isRecipientNoShow(c.getReason());
        if (isRecipientNoShow) {
            if (!bid.getSenderId().equals(callerId)) {
                throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                        "Vous n'êtes pas l'expéditeur de ce bid.");
            }
        } else {
            if (!announcement.getTravelerId().equals(callerId)) {
                throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                        "Vous n'êtes pas le voyageur de ce bid.");
            }
        }

        c.setNoShowStatus(CancellationStatus.CONTESTED);
        cancellationRepository.save(c);

        String disputeType = DeliveryNoShowTypes.contestedDisputeType(c.getReason());

        eventPublisher.publishEvent(new DisputeOpenedEvent(
                bidId, bid.getSenderId(), announcement.getTravelerId(), disputeType));
        auditService.log("CANCELLATION", c.getId(), "DELIVERY_NOSHOW_CONTESTED", callerId,
                Map.of("bidId", bidId.toString(), "type", disputeType));
    }

    /**
     * Annulation après remise du colis (HANDED_OVER) par l'expéditeur OU le voyageur.
     * Verrou D3, restauration du kilo, remboursement intégral (via per-bid
     * {@link TripCancelledEvent}), génération du code de retour (D7). MVP : aucune
     * pénalité monétaire.
     */
    @Transactional
    public void cancelAfterHandover(String firebaseUid, UUID bidId) {
        UserEntity caller = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElse(null);

        boolean isSender = bid.getSenderId().equals(caller.getId());
        boolean isTraveler = announcement != null
                && announcement.getTravelerId() != null
                && announcement.getTravelerId().equals(caller.getId());
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas partie prenante de ce bid.");
        }

        if (bid.getStatus() != BidStatus.HANDED_OVER) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "L'annulation après remise n'est possible que sur un colis remis (HANDED_OVER).");
        }

        CancellationGuard.assertCancellable(bid, announcement);

        if (cancellationRepository.findByBidId(bidId).isPresent()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-cancelled",
                    "Already Cancelled", "Une annulation existe déjà pour ce bid.");
        }

        CancellationActor actor = isSender ? CancellationActor.SENDER : CancellationActor.TRAVELER;
        CancellationReason reason = isSender
                ? CancellationReason.SENDER_CANCEL_AFTER_HANDOVER
                : CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER;

        // Restaurer le kilo au voyageur (sauf KG_FREE) + rouvrir l'annonce si FULL.
        if (announcement != null) {
            boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
            if (!isKgFree && bid.getWeightKg() != null) {
                announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
            }
            if (!isKgFree && announcement.getStatus() == AnnouncementStatus.FULL) {
                announcement.setStatus(AnnouncementStatus.ACTIVE);
            }
            announcementRepository.save(announcement);
        }

        // Code de retour : détenu par l'expéditeur, saisi par le voyageur (tranche C).
        LocalDateTime now = LocalDateTime.now();
        bid.setReturnCode(String.format("%06d", RETURN_CODE_RANDOM.nextInt(1_000_000)));
        bid.setReturnCodeExpiry(now.plusDays(3));
        bid.setReturnCodeAttempts(0);
        bid.setReturnDeadline(now.plusDays(3));
        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(caller.getId());
        c.setReason(reason.name());
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepository.save(c);

        // Réputation (D8 : l'annulation après remise est immédiatement CONFIRMED).
        // Voyageur → compteur d'annulations existant ; expéditeur → compteur d'incidents
        // de remise dédié (distinct du no-show voyageur).
        if (actor == CancellationActor.TRAVELER) {
            caller.setCancellationCount(caller.getCancellationCount() + 1);
        } else {
            caller.setSenderHandoverIncidentCount(caller.getSenderHandoverIncidentCount() + 1);
        }
        userRepository.save(caller);

        auditService.log("BID", bidId, "BID_CANCELLED_AFTER_HANDOVER", caller.getId(),
                Map.of("actor", actor.name(),
                       "paymentMethod",
                       bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE"));
        auditService.log("BID", bidId, "RETURN_CODE_GENERATED", caller.getId(),
                Map.of("returnDeadline", String.valueOf(bid.getReturnDeadline())));

        // Remboursement intégral réutilisant la matrice de TripCancelledEvent (per-bid).
        Map<UUID, String> bidPaymentMethods = new HashMap<>();
        Map<UUID, String> bidCommissionChargedVia = new HashMap<>();
        bidPaymentMethods.put(bidId,
                bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE");
        if (bid.getCommissionChargedVia() != null) {
            bidCommissionChargedVia.put(bidId, bid.getCommissionChargedVia().name());
        }
        eventPublisher.publishEvent(new TripCancelledEvent(
                bid.getAnnouncementId(),
                announcement != null ? announcement.getTravelerId() : null,
                List.of(bid.getSenderId()), reason.name(),
                List.of(bidId), bidPaymentMethods, bidCommissionChargedVia));
    }

    /**
     * Le voyageur saisit le code de retour (détenu par l'expéditeur) pour confirmer
     * la restitution physique du colis (D7). Réutilise la plomberie du confirmationCode
     * en sens inverse (expiry / tentatives / égalité). Publie {@link ParcelReturnedEvent}.
     */
    @Transactional
    public ReturnCodeResponse confirmReturn(String firebaseUid, UUID bidId, String code) {
        UserEntity caller = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        if (announcement.getTravelerId() == null
                || !announcement.getTravelerId().equals(caller.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur peut confirmer le retour du colis.");
        }
        if (bid.getReturnCode() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-not-generated",
                    "Code Not Generated", "Aucun retour de colis en attente pour ce bid.");
        }
        if (bid.getReturnedAt() != null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-returned",
                    "Already Returned", "Le colis a déjà été marqué comme rendu.");
        }
        if (bid.getReturnCodeExpiry() != null
                && LocalDateTime.now().isAfter(bid.getReturnCodeExpiry())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-expired",
                    "Code Expired", "Le code de retour a expiré — contactez le support.");
        }
        if (bid.getReturnCodeAttempts() >= MAX_RETURN_CODE_ATTEMPTS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-attempts",
                    "Too Many Attempts", "Trop de tentatives — contactez le support.");
        }
        if (!bid.getReturnCode().equals(code)) {
            bid.setReturnCodeAttempts(bid.getReturnCodeAttempts() + 1);
            bidRepository.save(bid);
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-incorrect",
                    "Code Incorrect", "Code de retour incorrect.");
        }

        bid.setReturnedAt(LocalDateTime.now());
        bid.setReturnCode(null);
        bid.setReturnCodeExpiry(null);
        bid.setReturnCodeAttempts(0);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "PARCEL_RETURNED", caller.getId(),
                Map.of("bidId", bidId.toString()));
        eventPublisher.publishEvent(
                new ParcelReturnedEvent(bidId, announcement.getTravelerId(), bid.getSenderId()));

        return new ReturnCodeResponse(null, bid.getReturnDeadline(), bid.getReturnedAt());
    }

    /** L'expéditeur consulte son code de retour (à communiquer au voyageur) + l'état du retour. */
    @Transactional(readOnly = true)
    public ReturnCodeResponse getReturnCode(String firebaseUid, UUID bidId) {
        UserEntity caller = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(caller.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut consulter le code de retour.");
        }
        return new ReturnCodeResponse(bid.getReturnCode(), bid.getReturnDeadline(), bid.getReturnedAt());
    }

    @Transactional
    public void confirmSenderNoShow(UUID bidId) {
        CancellationEntity c = cancellationRepository.findByBidId(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "cancellation-not-found", "Not Found",
                        "Aucune annulation en attente pour ce bid"));
        if (c.getNoShowStatus() != CancellationStatus.PENDING_CONFIRMATION) return;

        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepository.save(c);
        eventPublisher.publishEvent(
                new CancellationConfirmedEvent(bidId, c.getId(), CancellationReason.SENDER_NO_SHOW));
    }

    /**
     * Variante côté expéditeur : il confirme lui-même son absence (le bid sera
     * annulé, pas de débit). Vérifie qu'il est bien l'expéditeur du bid avant de
     * réutiliser la logique de confirmation.
     */
    @Transactional
    public void confirmSenderNoShow(UUID bidId, UUID senderId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce bid.");
        }
        confirmSenderNoShow(bidId);
    }

    @Transactional
    public void contestSenderNoShow(UUID bidId, UUID senderId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce bid.");
        }

        CancellationEntity c = cancellationRepository.findByBidId(bidId).orElseThrow();
        if (c.getContestationDeadline() == null
                || OffsetDateTime.now().isAfter(c.getContestationDeadline())) {
            throw new IllegalStateException("Le délai de contestation est dépassé.");
        }
        c.setNoShowStatus(CancellationStatus.CONTESTED);
        cancellationRepository.save(c);

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElseThrow();
        eventPublisher.publishEvent(new DisputeOpenedEvent(
                bidId, bid.getSenderId(), announcement.getTravelerId()));
        auditService.log("CANCELLATION", c.getId(), "SENDER_NO_SHOW_CONTESTED", senderId,
                Map.of("bidId", bidId.toString()));
    }

    private UserEntity findUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
    }
}

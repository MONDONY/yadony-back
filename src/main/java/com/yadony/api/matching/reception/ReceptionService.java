package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.reception.dto.ReceptionResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Les colis attendus par l'utilisateur courant, en tant que destinataire. */
@Service
public class ReceptionService {

    private static final Logger log = LoggerFactory.getLogger(ReceptionService.class);

    /** Un colis remis reste dans la liste deux semaines, le temps de le noter. */
    static final long COMPLETED_VISIBLE_DAYS = 14;

    private static final Set<ReceptionLinkStatus> VISIBLE_LINKS =
            EnumSet.of(ReceptionLinkStatus.PENDING, ReceptionLinkStatus.CONFIRMED);

    private final BidRecipientLinkRepository linkRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final ReceptionLinker linker;
    private final NotificationDispatcher notificationDispatcher;
    private final AuditService auditService;
    private final StorageService storageService;
    private final RatingRepository ratingRepository;
    private final Clock clock;

    @Autowired
    public ReceptionService(BidRecipientLinkRepository linkRepository,
                            BidRepository bidRepository,
                            AnnouncementRepository announcementRepository,
                            UserRepository userRepository,
                            ReceptionLinker linker,
                            NotificationDispatcher notificationDispatcher,
                            AuditService auditService,
                            StorageService storageService,
                            RatingRepository ratingRepository) {
        this(linkRepository, bidRepository, announcementRepository, userRepository, linker,
                notificationDispatcher, auditService, storageService, ratingRepository, Clock.systemUTC());
    }

    ReceptionService(BidRecipientLinkRepository linkRepository,
                     BidRepository bidRepository,
                     AnnouncementRepository announcementRepository,
                     UserRepository userRepository,
                     ReceptionLinker linker,
                     NotificationDispatcher notificationDispatcher,
                     AuditService auditService,
                     StorageService storageService,
                     RatingRepository ratingRepository,
                     Clock clock) {
        this.linkRepository = linkRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.linker = linker;
        this.notificationDispatcher = notificationDispatcher;
        this.auditService = auditService;
        this.storageService = storageService;
        this.ratingRepository = ratingRepository;
        this.clock = clock;
    }

    /**
     * Liens PENDING ou CONFIRMED de l'utilisateur, colis en cours (ou remis depuis moins
     * de 14 jours pour un lien confirmé), du plus récemment modifié au plus ancien.
     * Rattrape d'abord les colis acceptés avant que l'utilisateur n'ait l'app.
     */
    public List<ReceptionResponse> list(String firebaseUid) {
        UserEntity user = currentUser(firebaseUid);
        try {
            linker.catchUp(user);
        } catch (Exception e) {
            // Le rattrapage est un plus : la liste reste servie sans lui.
            log.warn("Rattrapage des réceptions impossible pour {} : {}", user.getId(), e.toString());
        }
        List<BidRecipientLinkEntity> links =
                linkRepository.findByRecipientUserIdAndStatusIn(user.getId(), VISIBLE_LINKS);
        if (links.isEmpty()) {
            return List.of();
        }
        Map<UUID, BidEntity> bids = bidRepository.findAllById(
                        links.stream().map(BidRecipientLinkEntity::getBidId).toList())
                .stream().collect(Collectors.toMap(BidEntity::getId, Function.identity()));
        LocalDateTime completedCutoff = LocalDateTime.now(clock.withZone(ZoneOffset.UTC))
                .minusDays(COMPLETED_VISIBLE_DAYS);
        return links.stream()
                .filter(l -> bids.containsKey(l.getBidId()))
                .filter(l -> isListed(l, bids.get(l.getBidId()), completedCutoff))
                .sorted(Comparator.comparing((BidRecipientLinkEntity l) -> bids.get(l.getBidId()).getUpdatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(l -> toResponse(l, bids.get(l.getBidId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReceptionResponse get(UUID bidId, String firebaseUid) {
        UserEntity user = currentUser(firebaseUid);
        BidRecipientLinkEntity link = visibleLink(bidId, user.getId());
        return toResponse(link, findBid(bidId));
    }

    /** « C'est pour moi » : idempotent sur un lien déjà confirmé. */
    @Transactional
    public ReceptionResponse confirm(UUID bidId, String firebaseUid) {
        UserEntity user = currentUser(firebaseUid);
        BidRecipientLinkEntity link = visibleLink(bidId, user.getId());
        BidEntity bid = findBid(bidId);
        if (link.getStatus() == ReceptionLinkStatus.PENDING) {
            link.respond(ReceptionLinkStatus.CONFIRMED, OffsetDateTime.now(clock));
            linkRepository.save(link);
            auditService.log("BID_RECIPIENT_LINK", link.getId(), "RECEPTION_CONFIRMED", user.getId(),
                    Map.of("bidId", bidId.toString()));
            var text = NotificationTexts.recipientConfirmed(
                    notificationDispatcher.messagesFor(bid.getSenderId()), user.getFirstName());
            notificationDispatcher.notifyUser(bid.getSenderId(), text.title(), text.body(),
                    Map.of("type", ReceptionNotifications.CONFIRMED, "bidId", bidId.toString()));
        }
        return toResponse(link, bid);
    }

    /**
     * « Ce n'est pas pour moi » avant confirmation, ou « Me retirer de ce
     * colis » après (FLUTTER-9F) : le destinataire de confiance, rattaché
     * d'office, ou celui qui a confirmé, peut se retirer tant que le colis
     * n'est pas livré. L'expéditeur est invité à désigner quelqu'un d'autre et
     * le voyageur est prévenu.
     */
    @Transactional
    public void decline(UUID bidId, String firebaseUid) {
        UserEntity user = currentUser(firebaseUid);
        BidRecipientLinkEntity link = linkRepository.findByBidIdAndRecipientUserId(bidId, user.getId())
                .orElseThrow(ReceptionService::notFound);
        BidEntity bid = findBid(bidId);
        if (link.getStatus() == ReceptionLinkStatus.DECLINED) {
            return;
        }
        if (link.getStatus() == ReceptionLinkStatus.CONFIRMED) {
            withdraw(link, bid, user);
            return;
        }
        link.respond(ReceptionLinkStatus.DECLINED, OffsetDateTime.now(clock));
        linkRepository.save(link);
        auditService.log("BID_RECIPIENT_LINK", link.getId(), "RECEPTION_DECLINED", user.getId(),
                Map.of("bidId", bidId.toString()));
        var text = NotificationTexts.recipientDeclined(notificationDispatcher.messagesFor(bid.getSenderId()));
        notificationDispatcher.notifyUser(bid.getSenderId(), text.title(), text.body(),
                Map.of("type", ReceptionNotifications.DECLINED, "bidId", bidId.toString()));
    }

    private void withdraw(BidRecipientLinkEntity link, BidEntity bid, UserEntity user) {
        if (!BidStatus.IN_FLIGHT.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "reception-not-withdrawable",
                    "Reception Not Withdrawable", "Ce colis est déjà livré ou n'est plus en cours");
        }
        link.respond(ReceptionLinkStatus.DECLINED, OffsetDateTime.now(clock));
        linkRepository.save(link);
        auditService.log("BID_RECIPIENT_LINK", link.getId(), "RECEPTION_WITHDRAWN", user.getId(),
                Map.of("bidId", bid.getId().toString(), "bidStatus", bid.getStatus().name()));
        Map<String, String> data = Map.of("type", ReceptionNotifications.WITHDRAWN, "bidId", bid.getId().toString());
        var toSender = NotificationTexts.recipientWithdrawnToSender(
                notificationDispatcher.messagesFor(bid.getSenderId()), user.getFirstName());
        notificationDispatcher.notifyUser(bid.getSenderId(), toSender.title(), toSender.body(), data);
        announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .ifPresent(travelerId -> {
                    var toTraveler = NotificationTexts.recipientWithdrawnToTraveler(
                            notificationDispatcher.messagesFor(travelerId), user.getFirstName());
                    notificationDispatcher.notifyUser(travelerId, toTraveler.title(), toTraveler.body(), data);
                });
    }

    private static boolean isListed(BidRecipientLinkEntity link, BidEntity bid, LocalDateTime completedCutoff) {
        if (BidStatus.IN_FLIGHT.contains(bid.getStatus())) {
            return true;
        }
        return bid.getStatus() == BidStatus.COMPLETED
                && link.getStatus() == ReceptionLinkStatus.CONFIRMED
                && bid.getUpdatedAt() != null
                && bid.getUpdatedAt().isAfter(completedCutoff);
    }

    ReceptionResponse toResponse(BidRecipientLinkEntity link, BidEntity bid) {
        boolean confirmed = link.getStatus() == ReceptionLinkStatus.CONFIRMED;
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        UserEntity sender = bid.getSenderId() != null
                ? userRepository.findById(bid.getSenderId()).orElse(null)
                : null;
        String senderFirstName = sender != null ? sender.getFirstName() : null;
        // Voyageur : seulement une fois le colis confirmé, comme son prénom. Son id ouvre
        // son profil public et son avatar s'affiche (Sentry FLUTTER-6F/6G/6H).
        UserEntity traveler = confirmed && announcement != null && announcement.getTravelerId() != null
                ? userRepository.findById(announcement.getTravelerId()).orElse(null)
                : null;
        String travelerFirstName = traveler != null ? traveler.getFirstName() : null;
        String code = confirmed && BidStatus.EN_ROUTE.contains(bid.getStatus()) ? bid.getConfirmationCode() : null;
        // Note du voyageur (FLUTTER-CA) : lue seulement pour un colis livré et confirmé.
        boolean rateable = confirmed && bid.getStatus() == BidStatus.COMPLETED;
        Integer myRating = rateable
                ? ratingRepository.findByBidIdAndRaterId(bid.getId(), link.getRecipientUserId())
                        .map(RatingEntity::getStars).orElse(null)
                : null;
        boolean canRate = rateable && myRating == null
                && !ratingRepository.recipientHasRated(bid.getId(), link.getRecipientUserId(), bid.getTrackingToken());
        return new ReceptionResponse(
                bid.getId(),
                link.getStatus().name(),
                bid.getStatus().name(),
                senderFirstName,
                announcement != null ? announcement.getDepartureCity() : null,
                announcement != null ? announcement.getArrivalCity() : null,
                announcement != null ? announcement.getDepartureDate() : null,
                announcement != null ? announcement.getArrivalDate() : null,
                bid.getRecipientName(),
                confirmed ? bid.getTrackingNumber() : null,
                travelerFirstName,
                confirmed && announcement != null ? announcement.getArrivalInstructions() : null,
                confirmed ? bid.getWeightKg() : null,
                code,
                latest(bid.getUpdatedAt(), link.getUpdatedAt()),
                traveler != null ? traveler.getId() : null,
                traveler != null ? storageService.avatarUrl(traveler.getAvatarUrl()) : null,
                sender != null ? sender.getId() : null,
                sender != null ? storageService.avatarUrl(sender.getAvatarUrl()) : null,
                canRate,
                myRating);
    }

    /** Dernière modification visible : le colis avance, ou le destinataire a répondu. */
    private static Instant latest(LocalDateTime bidUpdatedAt, LocalDateTime linkUpdatedAt) {
        LocalDateTime latest = java.util.stream.Stream.of(bidUpdatedAt, linkUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return latest != null ? latest.toInstant(ZoneOffset.UTC) : null;
    }

    /** Lien PENDING ou CONFIRMED de l'utilisateur ; un lien refusé n'existe plus pour lui. */
    private BidRecipientLinkEntity visibleLink(UUID bidId, UUID userId) {
        return linkRepository.findByBidIdAndRecipientUserId(bidId, userId)
                .filter(l -> l.getStatus() != ReceptionLinkStatus.DECLINED)
                .orElseThrow(ReceptionService::notFound);
    }

    private BidEntity findBid(UUID bidId) {
        return bidRepository.findById(bidId).orElseThrow(ReceptionService::notFound);
    }

    private UserEntity currentUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "reception-not-found",
                "Reception Not Found", "Colis introuvable");
    }
}

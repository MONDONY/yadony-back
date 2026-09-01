package com.yadony.api.ratings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.ratings.dto.RatingItemResponse;
import com.yadony.api.ratings.dto.RatingRequest;
import com.yadony.api.ratings.dto.RatingResponse;
import com.yadony.api.ratings.dto.RecipientRatingRequest;
import com.yadony.api.ratings.dto.TravelerRatingRequest;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.yadony.api.ratings.dto.PendingRatingResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.yadony.api.ratings.events.RatingCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);
    private static final int RATING_WINDOW_DAYS = 7;

    private final RatingRepository ratingRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final CancellationRepository cancellationRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final BlockVisibility blockVisibility;

    public RatingService(RatingRepository ratingRepository,
                         BidRepository bidRepository,
                         AnnouncementRepository announcementRepository,
                         UserRepository userRepository,
                         CancellationRepository cancellationRepository,
                         AuditService auditService,
                         ApplicationEventPublisher eventPublisher,
                         BlockVisibility blockVisibility) {
        this.ratingRepository = ratingRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.cancellationRepository = cancellationRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.blockVisibility = blockVisibility;
    }

    /**
     * L'expéditeur peut noter le voyageur si la livraison est confirmée (COMPLETED) OU si,
     * après une annulation post-remise par le voyageur (D5), le colis a bien été restitué
     * ({@code returnedAt != null}).
     */
    private boolean senderMayRate(BidEntity bid) {
        return bid.getStatus() == BidStatus.COMPLETED || bid.getReturnedAt() != null;
    }

    /**
     * Le voyageur peut noter l'expéditeur si la livraison est confirmée (COMPLETED) OU si un
     * no-show expéditeur a été confirmé pour ce bid (D6 : droit de notation/commentaire).
     */
    private boolean travelerMayRate(BidEntity bid) {
        if (bid.getStatus() == BidStatus.COMPLETED) {
            return true;
        }
        return cancellationRepository.findByBidId(bid.getId())
                .map(c -> CancellationReason.SENDER_NO_SHOW.name().equals(c.getReason())
                        && c.getNoShowStatus() == CancellationStatus.CONFIRMED)
                .orElse(false);
    }

    // Story 9.1 — Notation par l'expéditeur authentifié
    @Transactional
    public RatingResponse createRating(String firebaseUid, RatingRequest request) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));

        BidEntity bid = bidRepository.findById(request.bidId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Envoi introuvable"));

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de cet envoi");
        }

        if (!senderMayRate(bid)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-delivered",
                    "Unprocessable", "La livraison n'a pas encore été confirmée pour cet envoi");
        }

        if (bid.getUpdatedAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(RATING_WINDOW_DAYS))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "rating-window-expired",
                    "Unprocessable", "La fenêtre de notation est expirée");
        }

        if (ratingRepository.existsByBidIdAndRaterId(bid.getId(), sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-rated", "Conflict",
                    "Vous avez déjà noté ce voyageur pour cet envoi");
        }

        UUID travelerId = resolveTravelerId(bid);

        RatingEntity rating = new RatingEntity();
        rating.setRaterId(sender.getId());
        rating.setRatedUserId(travelerId);
        rating.setBidId(bid.getId());
        rating.setStars(request.stars());
        rating.setComment(request.comment());
        ratingRepository.save(rating);

        recalculateAverageRating(travelerId);

        auditService.log("RATING", rating.getId(), "RATING_CREATED", sender.getId(),
                Map.of("bidId", bid.getId().toString(), "stars", request.stars()));

        eventPublisher.publishEvent(new RatingCreatedEvent(rating.getId(), travelerId, sender.getId(), request.stars()));

        log.info("Rating created: bid={} rater={} stars={}", bid.getId(), sender.getId(), request.stars());
        return toResponse(rating);
    }

    // Story 9.2 — Notation par le destinataire sans compte
    @Transactional
    public RatingResponse createRecipientRating(RecipientRatingRequest request) {
        BidEntity bid = bidRepository.findByTrackingToken(request.trackingToken())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Lien de suivi invalide"));

        if (bid.getStatus() != BidStatus.COMPLETED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-delivered",
                    "Unprocessable", "La livraison n'a pas encore été confirmée");
        }

        if (ratingRepository.existsByBidIdAndTrackingToken(bid.getId(), request.trackingToken())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-rated", "Conflict",
                    "Vous avez déjà noté ce voyageur");
        }

        UUID travelerId = resolveTravelerId(bid);

        RatingEntity rating = new RatingEntity();
        rating.setRatedUserId(travelerId);
        rating.setBidId(bid.getId());
        rating.setTrackingToken(request.trackingToken());
        rating.setStars(request.stars());
        rating.setComment(request.comment());
        ratingRepository.save(rating);

        recalculateAverageRating(travelerId);

        auditService.log("RATING", rating.getId(), "RECIPIENT_RATING_CREATED", null,
                Map.of("bidId", bid.getId().toString(), "stars", request.stars()));

        eventPublisher.publishEvent(new RatingCreatedEvent(rating.getId(), travelerId, null, request.stars()));

        log.info("Recipient rating created: bid={} stars={}", bid.getId(), request.stars());
        return toResponse(rating);
    }

    // Story 9.3 — Notation par le voyageur authentifié (expéditeur noté)
    @Transactional
    public RatingResponse createTravelerRating(String firebaseUid, TravelerRatingRequest request) {
        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));

        BidEntity bid = bidRepository.findById(request.bidId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Envoi introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "data-inconsistency", "Error",
                        "Annonce introuvable pour cet envoi"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas le voyageur de cet envoi");
        }

        if (!travelerMayRate(bid)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-delivered",
                    "Unprocessable", "La livraison n'a pas encore été confirmée pour cet envoi");
        }

        if (bid.getUpdatedAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(RATING_WINDOW_DAYS))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "rating-window-expired",
                    "Unprocessable", "La fenêtre de notation est expirée");
        }

        if (ratingRepository.existsByBidIdAndRaterId(bid.getId(), traveler.getId())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-rated", "Conflict",
                    "Vous avez déjà noté cet expéditeur pour cet envoi");
        }

        UUID senderId = bid.getSenderId();

        RatingEntity rating = new RatingEntity();
        rating.setRaterId(traveler.getId());
        rating.setRatedUserId(senderId);
        rating.setBidId(bid.getId());
        rating.setStars(request.stars());
        rating.setComment(request.comment());
        ratingRepository.save(rating);

        recalculateAverageRating(senderId);

        auditService.log("RATING", rating.getId(), "TRAVELER_RATING_CREATED", traveler.getId(),
                Map.of("bidId", bid.getId().toString(), "stars", request.stars()));

        eventPublisher.publishEvent(new RatingCreatedEvent(rating.getId(), senderId, traveler.getId(), request.stars()));

        log.info("Traveler rating created: bid={} rater={} stars={}", bid.getId(), traveler.getId(), request.stars());
        return toResponse(rating);
    }

    public UserRatingsSummaryResponse getMyReceivedRatings(String firebaseUid, int page, int size) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
        // Ses propres notes : l'utilisateur est son propre viewer, aucun masquage possible.
        return getUserRatings(user.getId(), page, size, user.getId());
    }

    /**
     * Notes reçues par {@code userId}, telles que les voit {@code viewerId}
     * ({@code null} pour un appelant anonyme, qui ne subit aucun masquage).
     *
     * <p>Si l'utilisateur noté est masqué pour le viewer, on lève un 404 avant toute
     * lecture : la fiche de réputation ne doit pas rester un moyen de consulter un
     * compte bloqué.
     *
     * <p><b>Décision produit assumée :</b> le masquage porte sur le profil noté, pas sur
     * l'historique de réputation. Les notes écrites par des utilisateurs bloqués restent
     * affichées et continuent de compter dans la moyenne et la distribution. Retirer ces
     * avis réécrirait la réputation publique d'un tiers au gré des blocages de chacun, et
     * donnerait deux moyennes différentes selon qui regarde : on préfère une réputation
     * stable, identique pour tout le monde.
     */
    public UserRatingsSummaryResponse getUserRatings(UUID userId, int page, int size, UUID viewerId) {
        blockVisibility.assertVisible(viewerId, userId);

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        Page<RatingEntity> ratingsPage = ratingRepository.findByRatedUserId(
                userId, PageRequest.of(page, size, Sort.by("createdAt").descending()));

        List<RatingEntity> allIncluded = ratingRepository.findIncludedRatingsByRatedUserId(userId);
        int ratingCount = allIncluded.size();

        Map<Integer, Long> distribution = new HashMap<>();
        for (int i = 1; i <= 5; i++) distribution.put(i, 0L);
        allIncluded.stream()
                .collect(Collectors.groupingBy(RatingEntity::getStars, Collectors.counting()))
                .forEach(distribution::put);

        List<RatingItemResponse> items = ratingsPage.getContent().stream().map(r -> {
            UserEntity author = r.getRaterId() != null
                    ? userRepository.findById(r.getRaterId()).orElse(null) : null;
            String dep = null, arr = null;
            BidEntity bid = bidRepository.findById(r.getBidId()).orElse(null);
            if (bid != null) {
                AnnouncementEntity ann = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
                if (ann != null) { dep = ann.getDepartureCity(); arr = ann.getArrivalCity(); }
            }
            return new RatingItemResponse(
                    r.getStars(), r.getComment(), r.getCreatedAt(), r.isExcludedFromAverage(),
                    authorShortName(author),
                    author != null ? author.getAvatarUrl() : null,
                    dep, arr);
        }).collect(Collectors.toList());

        return new UserRatingsSummaryResponse(
                user.getAverageRating(),
                ratingCount,
                distribution,
                items,
                page,
                ratingsPage.getTotalPages()
        );
    }

    public Optional<PendingRatingResponse> getPendingRating(String firebaseUid) {
        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));

        Optional<BidEntity> maybeBid = bidRepository.findPendingRatingForUser(caller.getId());
        if (maybeBid.isEmpty()) return Optional.empty();

        BidEntity bid = maybeBid.get();
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "data-inconsistency", "Error",
                        "Annonce introuvable"));

        boolean isTravelerRating = announcement.getTravelerId().equals(caller.getId());
        UUID otherPartyId = isTravelerRating ? bid.getSenderId() : announcement.getTravelerId();

        UserEntity otherParty = userRepository.findById(otherPartyId).orElse(null);
        String otherPartyName = buildDisplayName(otherParty);

        return Optional.of(new PendingRatingResponse(
                bid.getId(), otherPartyName, otherPartyId, bid.getUpdatedAt(), isTravelerRating));
    }

    /** « Prénom + initiale » sans PII complète. null si auteur inconnu. */
    /**
     * Délègue à {@link UserEntity#publicDisplayName()}.
     *
     * <p>Rendait {@code null} pour un auteur sans prénom, laissant l'avis sans signature.
     */
    private String authorShortName(UserEntity author) {
        if (author == null) return null;
        return author.publicDisplayName();
    }

    /** Délègue à {@link UserEntity#publicDisplayName()} : repli sur le username du compte. */
    private String buildDisplayName(UserEntity user) {
        if (user == null) return "Utilisateur";
        return user.publicDisplayName();
    }

    @Transactional
    public void recalculateAverageRating(UUID userId) {
        List<RatingEntity> ratings = ratingRepository.findIncludedRatingsByRatedUserId(userId);

        userRepository.findById(userId).ifPresent(user -> {
            user.setRatingCount(ratings.size());
            if (!ratings.isEmpty()) {
                double avg = ratings.stream().mapToInt(RatingEntity::getStars).average().orElse(0.0);
                user.setAverageRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
            }
            userRepository.save(user);
        });
    }

    // Traveler is the owner of the announcement this bid is placed on
    private UUID resolveTravelerId(BidEntity bid) {
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "data-inconsistency", "Error",
                        "Annonce introuvable pour cet envoi"));
        return announcement.getTravelerId();
    }

    private RatingResponse toResponse(RatingEntity r) {
        return new RatingResponse(r.getId(), r.getRatedUserId(), r.getBidId(),
                r.getStars(), r.getComment(), r.getCreatedAt());
    }
}

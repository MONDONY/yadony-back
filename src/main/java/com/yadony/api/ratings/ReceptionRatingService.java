package com.yadony.api.ratings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import com.yadony.api.ratings.dto.RatingResponse;
import com.yadony.api.ratings.dto.ReceptionRatingRequest;
import com.yadony.api.ratings.events.RatingCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Le destinataire qui suit son colis dans l'app (lien de réception CONFIRMED) note le
 * voyageur une fois le colis livré (Sentry FLUTTER-CA).
 *
 * <p>C'est la même note destinataire que celle du lien de suivi public
 * ({@link RatingService#createRecipientRating}), portée par un compte : elle enregistre
 * {@code rater_id} ET le {@code tracking_token} du colis. Une seule note destinataire par
 * colis, quel que soit le chemin ({@link RatingRepository#recipientHasRated}), et elle
 * compte dans la moyenne publique du voyageur comme les autres notes.
 */
@Service
public class ReceptionRatingService {

    private static final Logger log = LoggerFactory.getLogger(ReceptionRatingService.class);

    private final RatingRepository ratingRepository;
    private final RatingService ratingService;
    private final BidRepository bidRepository;
    private final BidRecipientLinkRepository linkRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public ReceptionRatingService(RatingRepository ratingRepository,
                                  RatingService ratingService,
                                  BidRepository bidRepository,
                                  BidRecipientLinkRepository linkRepository,
                                  AnnouncementRepository announcementRepository,
                                  UserRepository userRepository,
                                  AuditService auditService,
                                  ApplicationEventPublisher eventPublisher) {
        this.ratingRepository = ratingRepository;
        this.ratingService = ratingService;
        this.bidRepository = bidRepository;
        this.linkRepository = linkRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public RatingResponse rate(UUID bidId, String firebaseUid, ReceptionRatingRequest request) {
        UserEntity recipient = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        if (!linkRepository.existsByBidIdAndRecipientUserIdAndStatus(
                bidId, recipient.getId(), ReceptionLinkStatus.CONFIRMED)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "reception-not-recipient",
                    "Forbidden", "Vous n'êtes pas le destinataire confirmé de ce colis");
        }

        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "reception-not-found",
                        "Reception Not Found", "Colis introuvable"));

        if (bid.getStatus() != BidStatus.COMPLETED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "reception-rating-not-allowed",
                    "Reception Rating Not Allowed", "Le voyageur se note une fois le colis livré");
        }

        if (ratingRepository.recipientHasRated(bid.getId(), recipient.getId(), bid.getTrackingToken())) {
            throw alreadyRated();
        }

        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "data-inconsistency", "Error", "Annonce introuvable pour cet envoi"));

        RatingEntity rating = new RatingEntity();
        rating.setRaterId(recipient.getId());
        rating.setRatedUserId(travelerId);
        rating.setBidId(bid.getId());
        // Même jeton que la note anonyme : l'index unique (bid_id, tracking_token) interdit
        // une seconde note destinataire sur ce colis, par un chemin comme par l'autre.
        rating.setTrackingToken(bid.getTrackingToken());
        rating.setStars(request.stars());
        rating.setComment(request.comment());
        try {
            ratingRepository.saveAndFlush(rating);
        } catch (DataIntegrityViolationException e) {
            // Deux envois simultanés : le second bute sur l'index unique.
            throw alreadyRated();
        }

        ratingService.recalculateAverageRating(travelerId);

        auditService.log("RATING", rating.getId(), "RECEPTION_RATING_CREATED", recipient.getId(),
                Map.of("bidId", bid.getId().toString(), "stars", request.stars()));

        eventPublisher.publishEvent(new RatingCreatedEvent(
                rating.getId(), travelerId, recipient.getId(), request.stars()));

        log.info("Reception rating created: bid={} rater={} stars={}", bid.getId(), recipient.getId(),
                request.stars());
        return new RatingResponse(rating.getId(), rating.getRatedUserId(), rating.getBidId(),
                rating.getStars(), rating.getComment(), rating.getCreatedAt());
    }

    private static YadonyBusinessException alreadyRated() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "reception-already-rated",
                "Reception Already Rated", "Vous avez déjà noté le voyageur pour ce colis");
    }
}

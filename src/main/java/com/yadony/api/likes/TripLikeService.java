package com.yadony.api.likes;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.likes.dto.TripLikeResponse;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * « J'aime » public sur les trajets. Idempotent : aimer deux fois ou retirer un j'aime
 * absent ne change rien, y compris sous concurrence (INSERT ... ON CONFLICT DO NOTHING et
 * DELETE direct, voir {@link TripLikeRepository}).
 */
@Service
@Transactional
public class TripLikeService {

    private final TripLikeRepository tripLikeRepository;
    private final AnnouncementRepository announcementRepository;
    private final BlockVisibility blockVisibility;
    private final AuditService auditService;

    public TripLikeService(TripLikeRepository tripLikeRepository,
                           AnnouncementRepository announcementRepository,
                           BlockVisibility blockVisibility,
                           AuditService auditService) {
        this.tripLikeRepository = tripLikeRepository;
        this.announcementRepository = announcementRepository;
        this.blockVisibility = blockVisibility;
        this.auditService = auditService;
    }

    /**
     * @throws YadonyNotFoundException si le trajet n'est pas aimable (absent, hors ACTIVE/FULL,
     *                                 caché, ou d'un voyageur masqué par un blocage)
     * @throws YadonyBusinessException (422 {@code trip-like-own-trip}) sur son propre trajet
     */
    public TripLikeResponse like(UUID userId, UUID tripId) {
        requireLikeable(userId, tripId);
        int inserted = tripLikeRepository.insertIfAbsent(UUID.randomUUID(), userId, tripId);
        if (inserted > 0) {
            audit("TRIP_LIKE_ADDED", userId, tripId);
        }
        return new TripLikeResponse(tripLikeRepository.countActive(tripId), true);
    }

    /** Retire le j'aime s'il existe. Aucun contrôle sur le trajet : on doit pouvoir retirer
     *  un j'aime posé sur un trajet devenu annulé ou masqué. */
    public TripLikeResponse unlike(UUID userId, UUID tripId) {
        int deleted = tripLikeRepository.deleteActive(userId, tripId);
        if (deleted > 0) {
            audit("TRIP_LIKE_REMOVED", userId, tripId);
        }
        return new TripLikeResponse(tripLikeRepository.countActive(tripId), false);
    }

    /** Nombre de j'aime par trajet ; les trajets sans j'aime sont absents de la map. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> countsFor(Collection<UUID> tripIds) {
        if (tripIds == null || tripIds.isEmpty()) return Map.of();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : tripLikeRepository.countByTripIds(tripIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Parmi {@code tripIds}, ceux que {@code userId} aime. */
    @Transactional(readOnly = true)
    public Set<UUID> likedBy(UUID userId, Collection<UUID> tripIds) {
        if (userId == null || tripIds == null || tripIds.isEmpty()) return Set.of();
        return new HashSet<>(tripLikeRepository.findLikedTripIds(userId, tripIds));
    }

    private void requireLikeable(UUID userId, UUID tripId) {
        AnnouncementEntity trip = announcementRepository.findById(tripId)
                .orElseThrow(() -> notFound(tripId));
        boolean likeableStatus = trip.getStatus() == AnnouncementStatus.ACTIVE
                || trip.getStatus() == AnnouncementStatus.FULL;
        if (!likeableStatus || trip.isHidden()) {
            throw notFound(tripId);
        }
        UUID travelerId = trip.getTravelerId();
        if (userId.equals(travelerId)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                    "trip-like-own-trip", "problem.trip-like-own-trip");
        }
        if (travelerId != null && blockVisibility.isHidden(userId, travelerId)) {
            throw notFound(tripId);
        }
    }

    private static YadonyNotFoundException notFound(UUID tripId) {
        return YadonyNotFoundException.localized("problem.not-found.trip-id", tripId);
    }

    private void audit(String action, UUID userId, UUID tripId) {
        auditService.log("TRIP_LIKE", tripId, action, userId, Map.of("tripId", tripId.toString()));
    }
}

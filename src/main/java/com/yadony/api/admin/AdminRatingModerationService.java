package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.ratings.RatingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Exclusion et suppression d'un avis par l'équipe, partagées par la modération des avis
 * ({@code AdminRatingsController}) et par la résolution d'un signalement d'avis.
 *
 * <p>Les deux gestes recalculent la moyenne et le nombre d'avis du compte noté : sans ce
 * recalcul, la moyenne stockée gardait un avis qui ne comptait plus.
 */
@Service
public class AdminRatingModerationService {

    /** Longueur maximale du motif de suppression, conservé à vie dans {@code audit_log}. */
    static final int MAX_DELETE_REASON_LENGTH = 500;

    private final RatingRepository ratingRepo;
    private final AuditService auditService;
    private final RatingService ratingService;

    public AdminRatingModerationService(RatingRepository ratingRepo, AuditService auditService,
                                        RatingService ratingService) {
        this.ratingRepo = ratingRepo;
        this.auditService = auditService;
        this.ratingService = ratingService;
    }

    @Transactional
    public RatingEntity exclude(UUID ratingId, boolean excluded, String reason, UUID adminId) {
        RatingEntity rating = findOrThrow(ratingId);
        rating.setExcludedFromAverage(excluded);
        rating.setExcludedReason(excluded ? reason : null);
        ratingRepo.save(rating);
        // L'exclusion sort l'avis de la moyenne : la moyenne stockée doit suivre.
        recalculateAggregates(rating);
        auditService.log("RATING", ratingId, "RATING_EXCLUDED", adminId,
                Map.of("ratingId", ratingId.toString(), "reason", reason != null ? reason : ""));
        return rating;
    }

    /**
     * Suppression douce. Le motif (facultatif) part dans le détail de l'audit. Trop long, il
     * est REFUSÉ (400) plutôt que tronqué : audit_log est immuable, une trace amputée ne se
     * corrige jamais.
     */
    @Transactional
    public void delete(UUID ratingId, String reason, UUID adminId) {
        String normalizedReason = reason != null ? reason.trim() : "";
        if (normalizedReason.length() > MAX_DELETE_REASON_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "rating-delete-reason-too-long", "Rating Delete Reason Too Long",
                    "Le motif de suppression ne doit pas dépasser "
                            + MAX_DELETE_REASON_LENGTH + " caractères");
        }
        RatingEntity rating = findOrThrow(ratingId);
        rating.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        // Flush avant recalcul : la requête de moyenne doit déjà ignorer l'avis supprimé.
        ratingRepo.saveAndFlush(rating);
        recalculateAggregates(rating);
        auditService.log("RATING", ratingId, "RATING_DELETED", adminId,
                Map.of("ratingId", ratingId.toString(), "reason", normalizedReason));
    }

    /** Moyenne et nombre d'avis du compte noté, relus depuis les avis encore comptés. */
    void recalculateAggregates(RatingEntity rating) {
        if (rating.getRatedUserId() != null) {
            ratingService.recalculateAverageRating(rating.getRatedUserId());
        }
    }

    private RatingEntity findOrThrow(UUID id) {
        return ratingRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "rating-not-found", "Not Found", "Avis introuvable"));
    }
}

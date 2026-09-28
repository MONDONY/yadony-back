package com.yadony.api.admin.dto;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.ratings.RatingEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminRatingResponse(
        UUID id,
        UUID bidId,
        String raterName,
        String ratedName,
        int score,
        String comment,
        boolean flagged,
        boolean excluded,
        String excludedReason,
        LocalDateTime createdAt,
        /** Date de suppression (soft delete) ; {@code null} pour un avis visible. */
        LocalDateTime deletedAt,
        /** Email de l'admin auteur de la suppression, lu dans l'audit {@code RATING_DELETED}. */
        String deletedByAdminEmail,
        /** Motif saisi à la suppression (audit {@code RATING_DELETED}), éventuellement vide. */
        String deleteReason
) {
    public static AdminRatingResponse from(RatingEntity e, Map<UUID, UserEntity> users) {
        return from(e, users, null);
    }

    public static AdminRatingResponse from(RatingEntity e, Map<UUID, UserEntity> users,
                                           com.yadony.api.admin.DeletionTraceService.DeletionTrace trace) {
        return new AdminRatingResponse(
                e.getId(),
                e.getBidId(),
                userName(e.getRaterId(), users),
                userName(e.getRatedUserId(), users),
                e.getStars(),
                e.getComment(),
                e.isFlagged(),
                e.isExcludedFromAverage(),
                e.getExcludedReason(),
                e.getCreatedAt(),
                e.getDeletedAt(),
                e.getDeletedAt() != null && trace != null ? trace.adminEmail() : null,
                e.getDeletedAt() != null && trace != null ? trace.reason() : null
        );
    }

    private static String userName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) return null;
        UserEntity u = users.get(userId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }
}

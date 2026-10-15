package com.yadony.api.ratings.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un avis de la liste publique. {@code id} sert à le signaler depuis l'app
 * (cible {@code RATING} de {@code POST /reports}, guideline Apple 1.2).
 */
public record RatingItemResponse(
        UUID id,
        int stars,
        String comment,
        LocalDateTime createdAt,
        boolean excluded,
        String authorName,
        String authorAvatarUrl,
        String departureCity,
        String arrivalCity
) {}

package com.yadony.api.matching.dto;

public record MatchingRequestDto(
        String id,
        String tripId,
        String tripCorridor,
        String tripDepartureDate,
        /** Kilos restants du trajet ; null pour un trajet bateau au m³ (clé absente du JSON). */
        Double tripAvailableKg,
        String senderId,
        String senderName,
        String senderInitials,
        double senderRating,
        int senderTotalSent,
        double weightKg,
        String contentType,
        double budgetPerKg,
        String packagePhotoUrl,
        String messageExcerpt,
        int matchScore,
        String requestedAt,
        String currency
) {}

package com.yadony.api.matching.dto;

public record MatchingRequestDto(
        String id,
        String tripId,
        String tripCorridor,
        String tripDepartureDate,
        double tripAvailableKg,
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

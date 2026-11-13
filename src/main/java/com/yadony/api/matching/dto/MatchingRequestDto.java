package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        String currency,
        /** Mode du trajet (PLANE, BOAT, CAR, BUS…) ; null sans trajet (alerte de corridor), clé alors absente. */
        String tripTransportMode,
        /** Mode de la demande ; null = « peu importe », toujours sérialisé (clé présente, valeur null). */
        @JsonInclude(JsonInclude.Include.ALWAYS) String requestTransportMode
) {

    /** Ancienne forme (avant les modes de transport) : modes à null. */
    public MatchingRequestDto(String id, String tripId, String tripCorridor, String tripDepartureDate,
                              Double tripAvailableKg, String senderId, String senderName, String senderInitials,
                              double senderRating, int senderTotalSent, double weightKg, String contentType,
                              double budgetPerKg, String packagePhotoUrl, String messageExcerpt, int matchScore,
                              String requestedAt, String currency) {
        this(id, tripId, tripCorridor, tripDepartureDate, tripAvailableKg, senderId, senderName, senderInitials,
                senderRating, senderTotalSent, weightKg, contentType, budgetPerKg, packagePhotoUrl, messageExcerpt,
                matchScore, requestedAt, currency, null, null);
    }
}

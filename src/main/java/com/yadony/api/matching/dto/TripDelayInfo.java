package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.matching.TripDelayEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Un retard annoncé par le voyageur : la réponse de {@code POST /announcements/{id}/delays} et, sous
 * {@code latestTripDelay}, le dernier retard du trajet servi dans {@link BidResponse} et
 * {@link AnnouncementResponse}.
 *
 * @param reason        motif ({@code PORT_CONGESTION}, {@code WEATHER}, {@code EXTENDED_STOPOVER}, {@code OTHER})
 * @param arrivalDate   nouvelle arrivée estimée au plus tôt
 * @param arrivalDateMax nouvelle arrivée estimée au plus tard
 * @param announcedAt   instant de l'annonce (UTC)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TripDelayInfo(
        UUID id,
        String reason,
        LocalDate arrivalDate,
        LocalDate arrivalDateMax,
        String message,
        Instant announcedAt
) {

    public static TripDelayInfo from(TripDelayEntity delay) {
        if (delay == null) {
            return null;
        }
        return new TripDelayInfo(delay.getId(), delay.getReason().name(), delay.getNewArrivalDate(),
                delay.getNewArrivalDateMax(), delay.getMessage(),
                delay.getCreatedAt() == null ? null : delay.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}

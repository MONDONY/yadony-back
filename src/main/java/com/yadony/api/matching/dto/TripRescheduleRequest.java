package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yadony.api.matching.TripRescheduleReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Nouvelle date d'un trajet publié. La date limite de remise suit, comme à la publication. */
public record TripRescheduleRequest(
        @NotNull(message = "{validation.trip.departure-date.required}")
        LocalDate departureDate,

        @NotNull(message = "{validation.trip.departure-time.required}")
        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        // Absente = arrivée le jour du départ (FLUTTER-4E).
        LocalDate arrivalDate,

        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        // ISO-8601, comme AnnouncementRequest.handoverDeadline.
        LocalDateTime handoverDeadline,

        @NotNull TripRescheduleReason reason,

        @Size(max = 300) String note
) {}

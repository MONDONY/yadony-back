package com.yadony.api.matching.dto;

import com.yadony.api.matching.TripDelayReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Retard annoncé par le voyageur d'un trajet en cours : la nouvelle fourchette d'arrivée estimée
 * (début et fin, toujours toutes deux) et le motif. Le message est facultatif et relayé aux expéditeurs.
 */
public record TripDelayRequest(
        @NotNull(message = "{validation.trip-delay.reason.required}")
        TripDelayReason reason,

        @NotNull(message = "{validation.trip-delay.arrival-date.required}")
        LocalDate arrivalDate,

        @NotNull(message = "{validation.trip-delay.arrival-date-max.required}")
        LocalDate arrivalDateMax,

        @Size(max = 300, message = "{validation.trip-delay.message.size}")
        String message
) {}

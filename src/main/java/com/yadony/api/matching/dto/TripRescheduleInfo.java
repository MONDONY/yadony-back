package com.yadony.api.matching.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Dernier report du trajet d'un colis, servi dans {@link BidResponse}.
 *
 * @param decisionPending  l'expéditeur peut encore garder son colis ou se retirer
 * @param decisionDeadline jusqu'à quand (heure locale du trajet) ; sans réponse, le colis reste
 */
public record TripRescheduleInfo(
        UUID id,
        String reason,
        String note,
        LocalDate previousDepartureDate,
        LocalTime previousDepartureTime,
        LocalDate newDepartureDate,
        LocalTime newDepartureTime,
        LocalDateTime rescheduledAt,
        boolean decisionPending,
        LocalDateTime decisionDeadline
) {}

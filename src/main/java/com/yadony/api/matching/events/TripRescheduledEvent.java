package com.yadony.api.matching.events;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Publié par {@code TripRescheduleService} quand le voyageur reporte son trajet. Une
 * cible par colis touché : notifications/ et messaging/ préviennent chaque expéditeur
 * sans relire le trajet.
 *
 * @param decisionRequired vrai si l'expéditeur doit choisir entre garder son colis et
 *                         se retirer (colis accepté ou remis), faux s'il est seulement
 *                         informé (demande pas encore acceptée)
 */
public record TripRescheduledEvent(
        UUID announcementId,
        UUID rescheduleId,
        UUID travelerId,
        String reason,
        LocalDate previousDepartureDate,
        LocalDate newDepartureDate,
        LocalTime newDepartureTime,
        List<Target> targets
) {
    public record Target(UUID bidId, UUID senderId, boolean decisionRequired) {}
}

package com.yadony.api.matching.events;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Publié par {@code TripDelayService} quand le voyageur annonce un retard (bateau, mais aussi tout mode) :
 * notifications/ et messaging/ préviennent chaque expéditeur d'un colis non livré sans relire le trajet.
 * Une cible par colis touché : remis, en transit ou arrivé ; les colis livrés ou annulés n'y figurent pas.
 */
public record TripDelayedEvent(
        UUID announcementId,
        UUID delayId,
        UUID travelerId,
        String reason,
        LocalDate arrivalDate,
        LocalDate arrivalDateMax,
        String message,
        List<Target> targets
) {
    public record Target(UUID bidId, UUID senderId) {}

    /** Identifiants des colis prévenus. */
    public List<UUID> bidIds() {
        return targets.stream().map(Target::bidId).toList();
    }
}

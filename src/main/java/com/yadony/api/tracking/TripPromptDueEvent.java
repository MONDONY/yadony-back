package com.yadony.api.tracking;

import java.util.UUID;

/**
 * Un rappel du suivi voyageur est dû : le voyageur doit confirmer son départ ou signaler son
 * arrivée. Publié par {@link TripPromptScheduler} une fois la ligne réservée ; notifications/
 * l'envoie en push.
 */
public record TripPromptDueEvent(UUID announcementId, UUID travelerId, Kind kind) {
    public enum Kind { DEPARTURE, ARRIVAL }
}

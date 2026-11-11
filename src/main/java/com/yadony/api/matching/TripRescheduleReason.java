package com.yadony.api.matching;

/** Motif d'un report de trajet, affiché aux expéditeurs concernés. */
public enum TripRescheduleReason {
    /** Ancien motif, toujours accepté pour les apps déjà installées. */
    FLIGHT_CANCELLED,
    TRANSPORT_CANCELLED,
    POSTPONED,
    OTHER
}

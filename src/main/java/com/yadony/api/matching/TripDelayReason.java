package com.yadony.api.matching;

/**
 * Motif d'un retard annoncé par le voyageur d'un trajet en cours (V382), valable pour tous les modes de
 * transport. {@link #PORT_CONGESTION} est propre au bateau (refusé 422 sur tout autre mode) ;
 * {@link #TRANSPORT_DISRUPTION} est le motif neutre (bouchons, frontière, panne, grève, trafic perturbé).
 */
public enum TripDelayReason {
    PORT_CONGESTION,
    WEATHER,
    EXTENDED_STOPOVER,
    TRANSPORT_DISRUPTION,
    OTHER
}

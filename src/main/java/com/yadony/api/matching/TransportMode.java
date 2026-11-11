package com.yadony.api.matching;

public enum TransportMode {
    PLANE,
    CAR,
    TRAIN,
    BUS,
    BOAT,
    OTHER;

    /**
     * Une demande de colis qui fixe un mode n'est servie que par un trajet de ce mode.
     *
     * <p>{@code requested == null} signifie « peu importe » : tout trajet convient. Voiture et bus
     * sont interchangeables (même route, même durée d'arrivée) ; tous les autres modes exigent
     * l'égalité stricte.
     */
    public static boolean accepts(TransportMode requested, TransportMode offered) {
        if (requested == null) {
            return true;
        }
        if (requested == offered) {
            return true;
        }
        return isRoad(requested) && isRoad(offered);
    }

    private static boolean isRoad(TransportMode mode) {
        return mode == CAR || mode == BUS;
    }
}

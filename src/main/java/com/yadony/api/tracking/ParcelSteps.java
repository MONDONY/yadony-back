package com.yadony.api.tracking;

import java.util.List;

/** Libellés publics des étapes d'un colis, partagés par la page de suivi et la page du QR. */
final class ParcelSteps {

    private ParcelSteps() {
    }

    static String label(TrackingEventType type) {
        return switch (type) {
            case DEPART -> "Colis remis au voyageur";
            case TRANSIT -> "En transit";
            case ARRIVEE -> "Arrivée confirmée";
        };
    }

    static String currentStep(List<TrackingEventEntity> events) {
        boolean hasArrivee = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
        boolean hasTransit = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
        boolean hasDepart = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);
        if (hasArrivee) return "Livraison confirmée";
        if (hasTransit) return "En transit";
        if (hasDepart) return "Remis au voyageur, en route";
        return "En attente du scan de départ";
    }
}

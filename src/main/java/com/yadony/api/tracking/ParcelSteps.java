package com.yadony.api.tracking;

import com.yadony.api.matching.TransportMode;

import java.util.List;

/**
 * Libellés publics des étapes d'un colis, partagés par la page de suivi et la page du QR.
 *
 * <p>Les libellés dépendent du mode du trajet : en bateau, le colis est déposé à l'entrepôt avant la
 * traversée, il n'est pas « remis au voyageur ». Un mode inconnu ({@code null}) reçoit les libellés
 * neutres, jamais ceux d'un mode particulier.
 */
final class ParcelSteps {

    private ParcelSteps() {
    }

    static String label(TrackingEventType type) {
        return label(type, null);
    }

    static String label(TrackingEventType type, TransportMode mode) {
        return switch (type) {
            case DEPART -> mode == TransportMode.BOAT ? "Dépôt à l'entrepôt" : "Colis remis au voyageur";
            case TRANSIT -> "En transit";
            case ARRIVEE -> "Arrivée confirmée";
        };
    }

    static String currentStep(List<TrackingEventEntity> events) {
        return currentStep(events, null);
    }

    static String currentStep(List<TrackingEventEntity> events, TransportMode mode) {
        boolean hasArrivee = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
        boolean hasTransit = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
        boolean hasDepart = events.stream().anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);
        if (hasArrivee) return "Livraison confirmée";
        if (hasTransit) return "En transit";
        if (hasDepart) {
            return mode == TransportMode.BOAT ? "Déposé à l'entrepôt" : "Remis au voyageur, en route";
        }
        return "En attente du scan de départ";
    }
}

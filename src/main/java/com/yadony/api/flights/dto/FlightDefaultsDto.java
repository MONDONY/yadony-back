package com.yadony.api.flights.dto;

/**
 * Trajet proposé d'office dans le formulaire de recherche.
 *
 * @param source {@code search} (sa dernière recherche de billets), {@code corridor} (son
 *               corridor le plus récent : trajet, demande ou alerte) ou {@code none}
 *               (visiteur ou aucun historique : champs laissés vides)
 */
public record FlightDefaultsDto(String source, FlightPlaceDto origin, FlightPlaceDto destination) {
}

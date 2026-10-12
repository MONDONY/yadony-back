package com.yadony.api.flights.dto;

import java.util.List;

/**
 * Résultat d'une recherche, offres triées par prix croissant. Les prix sont indicatifs
 * (cache du partenaire) : l'app l'affiche, le prix définitif est celui du site partenaire.
 */
public record FlightSearchResponse(
        String originCode,
        String destinationCode,
        boolean roundTrip,
        String currency,
        List<FlightOfferDto> offers) {
}

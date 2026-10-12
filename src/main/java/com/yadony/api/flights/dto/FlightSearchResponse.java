package com.yadony.api.flights.dto;

import java.util.List;

/**
 * Résultat d'une recherche, offres triées par prix croissant. Les prix sont indicatifs
 * (cache du partenaire) : l'app l'affiche, le prix définitif est celui du site partenaire.
 *
 * @param searchPath chemin de recherche Aviasales du trajet ({@code /search/PAR1212ABJ1}), pour
 *                   « Voir toutes les compagnies » : à envoyer comme {@code offerPath} à
 *                   {@code POST /flights/clicks}. {@code null} sans jour de départ utilisable
 *                   (pas de date, ou mois en cours) : l'app masque alors le lien.
 */
public record FlightSearchResponse(
        String originCode,
        String destinationCode,
        boolean roundTrip,
        String currency,
        List<FlightOfferDto> offers,
        String searchPath) {
}

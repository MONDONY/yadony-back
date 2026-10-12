package com.yadony.api.flights.dto;

import java.math.BigDecimal;

/**
 * Une offre de vol prête à afficher.
 *
 * @param durationMinutes durée totale de l'aller, escales comprises
 * @param offerPath       chemin de l'offre chez le partenaire, à renvoyer tel quel à
 *                        {@code POST /flights/clicks} pour obtenir le lien affilié
 * @param logoUrl         logo de la compagnie (carré, déduit du code IATA), {@code null} si le code
 *                        est absent ou si le modèle d'URL n'est pas configuré
 */
public record FlightOfferDto(
        String originCode,
        String destinationCode,
        String originAirport,
        String destinationAirport,
        String departureAt,
        String returnAt,
        BigDecimal price,
        String currency,
        String airlineCode,
        String airlineName,
        String flightNumber,
        Integer transfers,
        Integer returnTransfers,
        Integer durationMinutes,
        String gate,
        String offerPath,
        String logoUrl) {
}

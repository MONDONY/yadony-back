package com.yadony.api.flights.dto;

import java.math.BigDecimal;

/**
 * Une offre de vol prête à afficher.
 *
 * @param durationMinutes champ {@code duration} de Travelpayouts : durée totale du voyage, escales
 *                        comprises. Aller simple : durée de l'aller ; aller-retour : aller + retour
 *                        (sens inchangé pour l'ancienne app).
 * @param offerPath       chemin de l'offre chez le partenaire, à renvoyer tel quel à
 *                        {@code POST /flights/clicks} pour obtenir le lien affilié
 * @param logoUrl         logo de la compagnie (carré, déduit du code IATA), {@code null} si le code
 *                        est absent ou si le modèle d'URL n'est pas configuré
 * @param outboundDurationMinutes durée de l'aller seul ({@code duration_to}), {@code null} si absente
 * @param returnDurationMinutes   durée du retour seul ({@code duration_back}), {@code null} si absente
 *                                ou aller simple
 * @param arrivalAt       arrivée de l'aller, ISO-8601 avec décalage, à l'heure locale de l'aéroport
 *                        d'arrivée (ex. {@code 2026-12-12T15:45:00Z}) ; {@code null} si la durée
 *                        de l'aller ou le fuseau est inconnu
 * @param returnArrivalAt arrivée du retour, à l'heure locale de l'aéroport de départ du voyage ;
 *                        {@code null} en aller simple, ou si la durée ou le fuseau est inconnu
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
        String logoUrl,
        Integer outboundDurationMinutes,
        Integer returnDurationMinutes,
        String arrivalAt,
        String returnArrivalAt) {
}

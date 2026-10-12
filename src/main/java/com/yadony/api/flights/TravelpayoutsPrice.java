package com.yadony.api.flights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Une ligne de {@code /aviasales/v3/prices_for_dates}, telle que Travelpayouts la
 * renvoie. Prix issus du cache d'Aviasales (recherches récentes de ses utilisateurs) :
 * indicatifs, jamais garantis — l'UI le dit à chaque affichage.
 *
 * <p>{@code duration} est la durée totale du voyage en minutes, escales comprises ;
 * {@code link} est un chemin relatif ({@code /search/...?t=...}) auquel on ajoute le
 * domaine et le marker partenaire pour obtenir le lien affilié.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TravelpayoutsPrice(
        String origin,
        String destination,
        @JsonProperty("origin_airport") String originAirport,
        @JsonProperty("destination_airport") String destinationAirport,
        @JsonProperty("departure_at") String departureAt,
        @JsonProperty("return_at") String returnAt,
        BigDecimal price,
        String airline,
        @JsonProperty("flight_number") String flightNumber,
        Integer transfers,
        @JsonProperty("return_transfers") Integer returnTransfers,
        Integer duration,
        String gate,
        String link) {
}

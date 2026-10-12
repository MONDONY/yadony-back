package com.yadony.api.flights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Une ligne de {@code /aviasales/v3/prices_for_dates}, telle que Travelpayouts la
 * renvoie. Prix issus du cache d'Aviasales (recherches récentes de ses utilisateurs) :
 * indicatifs, jamais garantis — l'UI le dit à chaque affichage.
 *
 * <p>{@code duration} est la durée totale du voyage en minutes, escales comprises : pour un
 * aller-retour, c'est la somme de l'aller et du retour. {@code duration_to} et
 * {@code duration_back} détaillent chaque trajet (absents de certaines lignes) ;
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
        String link,
        @JsonProperty("duration_to") Integer durationTo,
        @JsonProperty("duration_back") Integer durationBack) {

    /** Ligne sans le détail des durées aller et retour. */
    public TravelpayoutsPrice(String origin, String destination, String originAirport, String destinationAirport,
                              String departureAt, String returnAt, BigDecimal price, String airline,
                              String flightNumber, Integer transfers, Integer returnTransfers, Integer duration,
                              String gate, String link) {
        this(origin, destination, originAirport, destinationAirport, departureAt, returnAt, price, airline,
                flightNumber, transfers, returnTransfers, duration, gate, link, null, null);
    }
}

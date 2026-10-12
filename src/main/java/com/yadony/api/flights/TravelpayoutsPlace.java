package com.yadony.api.flights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Une suggestion de l'autocomplétion Travelpayouts ({@code places2}). {@code type}
 * vaut {@code city} ou {@code airport} ; pour une ville, {@code code} est le code IATA
 * de la ville (PAR pour Paris, qui regroupe CDG, ORY et BVA).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TravelpayoutsPlace(
        String type,
        String code,
        String name,
        @JsonProperty("country_code") String countryCode,
        @JsonProperty("country_name") String countryName,
        @JsonProperty("city_name") String cityName) {
}

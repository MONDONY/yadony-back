package com.yadony.api.flights.dto;

/** Suggestion d'autocomplétion : {@code code} IATA de la ville (PAR, ABJ…). */
public record FlightPlaceDto(String code, String name, String countryCode, String countryName) {
}

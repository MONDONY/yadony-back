package com.yadony.api.matching.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Résumé d'une étape d'un voyage, pour la fiche d'une étape et le back-office. */
public record TripLegSummary(
        UUID id,
        int legIndex,
        String departureCity,
        String arrivalCity,
        String departureCountryCode,
        String arrivalCountryCode,
        LocalDate departureDate,
        LocalDate arrivalDate,
        String status
) {}

package com.yadony.api.flights.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Création d'une alerte prix sur une période (31 jours au plus). Les dates sont au format
 * AAAA-MM-JJ, bornes incluses ; sans période de départ, toutes les dates à venir ; période de
 * retour présente = aller-retour.
 */
public record FlightAlertRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String originCode,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String destinationCode,
        @Size(max = 120) String originLabel,
        @Size(max = 120) String destinationLabel,
        LocalDate departureFrom,
        LocalDate departureTo,
        LocalDate returnFrom,
        LocalDate returnTo,
        boolean directOnly,
        @NotNull @DecimalMin(value = "1") @DecimalMax(value = "100000000") BigDecimal maxPrice,
        String currency) {
}

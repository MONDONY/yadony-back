package com.yadony.api.flights.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Création d'une alerte prix. Les mois sont au format AAAA-MM ; {@code returnMonth}
 * présent = aller-retour.
 */
public record FlightAlertRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String originCode,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String destinationCode,
        @Size(max = 120) String originLabel,
        @Size(max = 120) String destinationLabel,
        String departureMonth,
        String returnMonth,
        boolean directOnly,
        @NotNull @DecimalMin(value = "1") @DecimalMax(value = "100000000") BigDecimal maxPrice,
        String currency) {
}

package com.yadony.api.flights.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/** Modification partielle : chaque champ {@code null} reste inchangé. */
public record FlightAlertUpdateRequest(
        Boolean active,
        @DecimalMin(value = "1") @DecimalMax(value = "100000000") BigDecimal maxPrice) {
}

package com.yadony.api.requests.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Body for POST /negotiations/trip/{announcementId}/open-surplus.
 * surplusKg: kg supplémentaires ouverts au public (≥ 1).
 * pricePerKg: prix libre du surplus (> 0).
 */
public record OpenSurplusRequest(
        // Maximum technique (NUMERIC(8,2), V380) : au-delà, 422 plutôt qu'une 500 SQL.
        @NotNull @DecimalMin("1.0")
        @DecimalMax(value = "999999.99", message = "{validation.surplus.kg.max}") BigDecimal surplusKg,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal pricePerKg
) {}

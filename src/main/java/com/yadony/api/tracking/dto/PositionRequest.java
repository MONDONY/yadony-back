package com.yadony.api.tracking.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Position brute envoyée par l'app du voyageur. Elle ne sert qu'à être floutée : jamais
 * stockée, jamais journalisée (d'où le {@code toString} qui ne la montre pas).
 */
public record PositionRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon) {

    @Override
    public String toString() {
        return "PositionRequest[masquée]";
    }
}

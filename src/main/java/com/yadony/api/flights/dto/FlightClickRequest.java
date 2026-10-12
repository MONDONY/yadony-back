package com.yadony.api.flights.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Offre choisie par l'utilisateur. Seul {@code offerPath} sert à construire le lien ;
 * le reste décrit le clic pour les statistiques.
 *
 * @param source écran d'origine : {@code search}, {@code deal} ou {@code alert}
 */
public record FlightClickRequest(
        @NotBlank @Size(max = 2000) String offerPath,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String originCode,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String destinationCode,
        @DecimalMin("0") BigDecimal price,
        @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
        @Pattern(regexp = "^[A-Za-z0-9]{2,3}$") String airlineCode,
        @Size(max = 100) String gate,
        @NotBlank @Pattern(regexp = "^(search|deal|alert)$") String source) {
}

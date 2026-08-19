package com.yadony.api.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UserBusinessPrefsDto(
    @NotNull @Pattern(regexp = "kg|lbs") String weightUnit,
    @NotNull @Pattern(regexp = "EUR|USD|CAD|GBP|CHF|XOF|XAF") String currencyCode,
    @NotNull @Min(1) @Max(50) Integer pickupRadiusKm,
    @NotNull @Min(1) @Max(50) Integer defaultPackageWeightKg,
    @NotNull @Min(0) @Max(50) Integer minBidPriceEur,
    @Pattern(regexp = "call|message|both") String contactMode,
    @Min(1) Integer responseDelayHours,
    // Lecture seule : renseigné par le serveur en réponse (GET/PUT), ignoré s'il
    // est envoyé dans une requête. Pas de contrainte @NotNull — un client qui
    // n'omet ce champ dans son PUT ne doit jamais faire échouer la validation.
    Boolean currencyLocked
) {
    public static UserBusinessPrefsDto defaults() {
        return new UserBusinessPrefsDto("kg", "EUR", 10, 23, 0, null, null, false);
    }
}

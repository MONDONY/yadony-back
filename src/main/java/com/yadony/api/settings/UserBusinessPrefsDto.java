package com.yadony.api.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UserBusinessPrefsDto(
    @NotNull @Pattern(regexp = "kg|lbs") String weightUnit,
    // Lecture seule depuis le 2026-08-19 : la devise derive du pays, un PUT qui la
    // porte est ignore. Le motif reste large pour ne jamais faire echouer un client
    // qui renvoie la valeur qu'il vient de lire.
    @Pattern(regexp = "EUR|USD|CAD|GBP|CHF|XOF|XAF") String currencyCode,
    @NotNull @Min(1) @Max(50) Integer pickupRadiusKm,
    @NotNull @Min(1) @Max(50) Integer defaultPackageWeightKg,
    @NotNull @Min(0) @Max(50) Integer minBidPriceEur,
    @Pattern(regexp = "call|message|both") String contactMode,
    @Min(1) Integer responseDelayHours,
    // Lecture seule : renseigné par le serveur en réponse (GET/PUT), ignoré s'il
    // est envoyé dans une requête. Pas de contrainte @NotNull — un client qui
    // n'omet ce champ dans son PUT ne doit jamais faire échouer la validation.
    Boolean currencyLocked,
    // Pays ISO 3166-1 alpha-2, ou null tant qu'il n'est pas renseigne.
    @Pattern(regexp = "[A-Z]{2}") String country,
    // Lecture seule : renseigne par le serveur en reponse, ignore en requete.
    Boolean countryLocked
) {
    public static UserBusinessPrefsDto defaults() {
        return new UserBusinessPrefsDto(
                "kg", "EUR", 10, 23, 0, null, null, false, null, false);
    }

    public UserBusinessPrefsDto withCountry(String iso2) {
        return new UserBusinessPrefsDto(weightUnit, currencyCode, pickupRadiusKm,
                defaultPackageWeightKg, minBidPriceEur, contactMode,
                responseDelayHours, currencyLocked, iso2, countryLocked);
    }
}

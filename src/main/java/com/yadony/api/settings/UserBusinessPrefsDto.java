package com.yadony.api.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UserBusinessPrefsDto(
    @NotNull @Pattern(regexp = "kg|lbs") String weightUnit,
    // Champ d'ecriture : modifiable tant que CurrencyLockService.isLocked rend false
    // (portefeuille vide). Omis dans la requete, la devise existante est conservee.
    @Pattern(regexp = "EUR|USD|CAD|GBP|CHF|XOF|XAF") String currencyCode,
    @NotNull @Min(1) @Max(50) Integer pickupRadiusKm,
    @NotNull @Min(1) @Max(50) Integer defaultPackageWeightKg,
    @NotNull @Min(0) @Max(50) Integer minBidPriceEur,
    @Pattern(regexp = "call|message|both") String contactMode,
    @Min(1) Integer responseDelayHours,
    // Lecture seule : renseigné par le serveur en réponse (GET/PUT) à partir de
    // CurrencyLockService, ignoré s'il est envoyé dans une requête. Pas de
    // contrainte @NotNull — un client qui l'omet dans son PUT ne doit jamais faire
    // échouer la validation.
    Boolean currencyLocked,
    // Pays ISO 3166-1 alpha-2, ou null tant qu'il n'est pas renseigne.
    @Pattern(regexp = "[A-Z]{2}") String country,
    // Lecture seule : renseigne par le serveur en reponse, ignore en requete.
    Boolean countryLocked,
    // Devise d'affichage (presentment, lot 8) : "AUTO" = suivre la devise active
    // (comportement historique), sinon une des 7 devises. Jamais verrouillee par le
    // solde. Omise dans la requete (null), la valeur existante est conservee ; le
    // serveur repond toujours une valeur concrete ("AUTO" quand rien n'est fige).
    @Pattern(regexp = "AUTO|EUR|USD|CAD|GBP|CHF|XOF|XAF") String displayCurrencyCode
) {
    public static UserBusinessPrefsDto defaults() {
        return new UserBusinessPrefsDto(
                "kg", "EUR", 10, 23, 0, null, null, false, null, false, "AUTO");
    }

    /** Valeur initiale (derivee du pays) quand aucune ligne de portefeuille n'existe encore. */
    public UserBusinessPrefsDto withCurrencyCode(String code) {
        return new UserBusinessPrefsDto(weightUnit, code, pickupRadiusKm,
                defaultPackageWeightKg, minBidPriceEur, contactMode,
                responseDelayHours, currencyLocked, country, countryLocked,
                displayCurrencyCode);
    }

    public UserBusinessPrefsDto withCountry(String iso2) {
        return new UserBusinessPrefsDto(weightUnit, currencyCode, pickupRadiusKm,
                defaultPackageWeightKg, minBidPriceEur, contactMode,
                responseDelayHours, currencyLocked, iso2, countryLocked,
                displayCurrencyCode);
    }
}

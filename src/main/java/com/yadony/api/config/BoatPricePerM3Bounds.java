package com.yadony.api.config;

import java.math.BigDecimal;

/**
 * Bornes du prix au m³ d'un trajet bateau, en euros (lot 4 bateau) : bornes dures appliquées à la saisie,
 * zone conseillée et prix indicatif pour l'aide à la saisie de l'application. Toujours ordonnées
 * {@code hardMin ≤ advisedMin ≤ reference ≤ advisedMax ≤ hardMax}. Voir
 * {@link PlatformSettingKey#BOAT_PRICE_PER_M3_HARD_MIN_EUR}.
 */
public record BoatPricePerM3Bounds(
        BigDecimal hardMinEur,
        BigDecimal hardMaxEur,
        BigDecimal advisedMinEur,
        BigDecimal advisedMaxEur,
        BigDecimal referenceEur) {

    public static final BoatPricePerM3Bounds DEFAULTS = new BoatPricePerM3Bounds(
            PlatformSettingKey.DEFAULT_BOAT_PRICE_PER_M3_HARD_MIN_EUR,
            PlatformSettingKey.DEFAULT_BOAT_PRICE_PER_M3_HARD_MAX_EUR,
            PlatformSettingKey.DEFAULT_BOAT_PRICE_PER_M3_ADVISED_MIN_EUR,
            PlatformSettingKey.DEFAULT_BOAT_PRICE_PER_M3_ADVISED_MAX_EUR,
            PlatformSettingKey.DEFAULT_BOAT_PRICE_PER_M3_REFERENCE_EUR);

    /** Vrai si les cinq valeurs sont dans l'ordre attendu. */
    public boolean isOrdered() {
        return hardMinEur.compareTo(advisedMinEur) <= 0
                && advisedMinEur.compareTo(referenceEur) <= 0
                && referenceEur.compareTo(advisedMaxEur) <= 0
                && advisedMaxEur.compareTo(hardMaxEur) <= 0;
    }
}

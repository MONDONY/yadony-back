package com.yadony.api.config.dto;

import com.yadony.api.config.BoatPricePerM3Bounds;

import java.math.BigDecimal;

/**
 * Bornes du prix au m³ d'un trajet bateau, en euros : {@code hardMinEur} et {@code hardMaxEur} sont appliquées
 * par le serveur, {@code advisedMinEur}, {@code advisedMaxEur} et {@code referenceEur} guident la saisie.
 */
public record BoatPriceBoundsResponse(
        BigDecimal hardMinEur,
        BigDecimal hardMaxEur,
        BigDecimal advisedMinEur,
        BigDecimal advisedMaxEur,
        BigDecimal referenceEur) {

    public static BoatPriceBoundsResponse from(BoatPricePerM3Bounds bounds) {
        return new BoatPriceBoundsResponse(bounds.hardMinEur(), bounds.hardMaxEur(), bounds.advisedMinEur(),
                bounds.advisedMaxEur(), bounds.referenceEur());
    }
}

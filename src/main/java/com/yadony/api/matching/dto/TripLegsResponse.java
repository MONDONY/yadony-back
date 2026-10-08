package com.yadony.api.matching.dto;

import java.util.List;
import java.util.UUID;

/**
 * Étapes du voyage auquel appartient une annonce. Pour un trajet isolé,
 * {@code tripGroupId} est null et {@code legs} est vide.
 *
 * <p>{@code legCount} compte toutes les étapes encore présentes du voyage, même celles
 * que le lecteur ne voit pas (brouillon, retirée) : « Étape 2/3 » reste vrai pour lui.
 */
public record TripLegsResponse(UUID tripGroupId, int legCount, List<TripLegSummary> legs) {

    public static TripLegsResponse none() {
        return new TripLegsResponse(null, 0, List.of());
    }
}

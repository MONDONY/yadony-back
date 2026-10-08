package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Voyage à plusieurs étapes (FLUTTER-4D) : chaque étape est une annonce de trajet
 * classique, publiée avec les autres en une seule transaction. Le chaînage (ville de
 * départ = ville d'arrivée de l'étape précédente, départ au plus tôt le jour de
 * l'arrivée précédente) est contrôlé par {@link com.yadony.api.matching.TripLegRules}.
 */
public record TripRequest(
        @NotNull(message = "{validation.trip.legs.required}")
        @Size(min = 2, max = 5, message = "{validation.trip.legs.size}")
        List<@Valid @NotNull AnnouncementRequest> legs
) {}

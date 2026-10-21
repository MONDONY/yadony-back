package com.yadony.api.matching.dto;

import java.util.List;

/**
 * Annonce du même trajet qui ne respecte pas tous les critères souples de la recherche
 * (bloc 2 de {@code GET /announcements}). {@code unmet} n'est jamais vide : une annonce
 * n'apparaît pas dans ce bloc sans l'explication de ce qui ne correspond pas.
 * Aucun score de classement n'est exposé ici.
 */
public record SimilarAnnouncementResponse(AnnouncementSearchResponse announcement, List<UnmetCriterionDto> unmet) {

    public SimilarAnnouncementResponse {
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
    }
}

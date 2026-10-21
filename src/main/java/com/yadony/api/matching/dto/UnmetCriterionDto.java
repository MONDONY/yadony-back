package com.yadony.api.matching.dto;

import com.yadony.api.matching.ranking.UnmetCriterion;

/**
 * Critère de recherche qu'une annonce du bloc « Autres annonces sur ce trajet » ne respecte pas.
 *
 * @param code      DATE, AVAILABLE_KG (kilos sous le minimum),
 *                  MAX_AVAILABLE_KG (kilos au-dessus du maximum), TRANSPORT_MODE, MIN_RATING, CONTENT_TYPE, RADIUS,
 *                  WEEKEND_ONLY, URGENT ou KILO_PRO_ONLY (traduit par l'app)
 * @param requested ce que l'expéditeur a demandé
 * @param actual    ce que le trajet offre
 */
public record UnmetCriterionDto(String code, String requested, String actual) {

    public static UnmetCriterionDto from(UnmetCriterion c) {
        return new UnmetCriterionDto(c.code(), c.requested(), c.actual());
    }
}

package com.yadony.api.matching.ranking;

/**
 * Un critère de recherche qu'un trajet ne respecte pas.
 *
 * @param code      DATE, AVAILABLE_KG (kilos sous le minimum),
 *                  MAX_AVAILABLE_KG (kilos au-dessus du maximum), TRANSPORT_MODE, MIN_RATING, CONTENT_TYPE, RADIUS,
 *                  WEEKEND_ONLY, URGENT ou KILO_PRO_ONLY
 * @param requested ce que l'utilisateur a demandé (lisible)
 * @param actual    ce que le trajet offre (lisible)
 */
public record UnmetCriterion(String code, String requested, String actual) {
}

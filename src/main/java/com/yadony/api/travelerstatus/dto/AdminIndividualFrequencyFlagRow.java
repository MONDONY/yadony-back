package com.yadony.api.travelerstatus.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne de la file « Particuliers à confirmer » (lot 4).
 *
 * @param tripsCount trajets publiés (brouillons, trajets annulés et retirés exclus) dans le mois civil de la
 *        détection, à la détection
 * @param windowDays nombre de jours du mois civil compté (28 à 31) ; 30 pour un signalement créé avant le passage
 *        au mois civil (ancienne règle : 30 jours glissants, trajets avec réservation acceptée)
 * @param periodMonth mois civil compté, {@code AAAA-MM} (fuseau Europe/Paris, d'après {@code detectedAt})
 * @param weeklyApplication vrai si une demande GP Pro « chaque semaine », refusée ou annulée, a aussi déclenché le
 *        signalement (le compte peut alors être sous le seuil)
 */
public record AdminIndividualFrequencyFlagRow(UUID flagId, UUID userId, String displayName, int tripsCount,
                                              int windowDays, int threshold, LocalDateTime detectedAt,
                                              boolean weeklyApplication, String periodMonth) {
}

package com.yadony.api.travelerstatus.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne de la file « Particuliers à confirmer » (lot 4).
 *
 * @param tripsCount trajets distincts avec une réservation acceptée sur la fenêtre, à la détection
 * @param weeklyApplication vrai si une demande GP Pro « chaque semaine », refusée ou annulée, a aussi déclenché le
 *        signalement (le compte peut alors être sous le seuil)
 */
public record AdminIndividualFrequencyFlagRow(UUID flagId, UUID userId, String displayName, int tripsCount,
                                              int windowDays, int threshold, LocalDateTime detectedAt,
                                              boolean weeklyApplication) {
}

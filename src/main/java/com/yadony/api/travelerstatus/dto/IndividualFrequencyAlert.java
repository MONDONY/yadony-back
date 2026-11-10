package com.yadony.api.travelerstatus.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bandeau de la fiche utilisateur admin : signalement « particulier à confirmer » ouvert (ni résolu ni écarté).
 * Absent de la fiche sinon.
 */
public record IndividualFrequencyAlert(UUID flagId, int tripsCount, int windowDays, int threshold,
                                       LocalDateTime detectedAt, boolean weeklyApplication) {
}

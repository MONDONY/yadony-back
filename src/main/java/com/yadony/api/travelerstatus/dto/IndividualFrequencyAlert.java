package com.yadony.api.travelerstatus.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bandeau de la fiche utilisateur admin : signalement « particulier à confirmer » ouvert (ni résolu ni écarté).
 * Absent de la fiche sinon.
 *
 * @param tripsCount trajets publiés (brouillons, trajets annulés et retirés exclus) dans le mois civil de la
 *        détection : « N trajets publiés ce mois-ci »
 * @param windowDays nombre de jours du mois civil compté (28 à 31) ; 30 pour un signalement créé avant le passage
 *        au mois civil (ancienne règle : 30 jours glissants, trajets avec réservation acceptée)
 * @param periodMonth mois civil compté, {@code AAAA-MM} (fuseau Europe/Paris, d'après {@code detectedAt})
 */
public record IndividualFrequencyAlert(UUID flagId, int tripsCount, int windowDays, int threshold,
                                       LocalDateTime detectedAt, boolean weeklyApplication, String periodMonth) {
}

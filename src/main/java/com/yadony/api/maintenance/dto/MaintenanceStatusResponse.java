package com.yadony.api.maintenance.dto;

import java.time.Instant;

/**
 * Réponse publique de {@code GET /config/maintenance}.
 *
 * @param message        texte personnalisé saisi dans le back-office, null = texte par défaut de l'application
 * @param estimatedEndAt heure de retour estimée (UTC), facultative
 * @param exempt         vrai si l'appelant authentifié peut utiliser l'application malgré la maintenance
 */
public record MaintenanceStatusResponse(boolean enabled, String message, Instant estimatedEndAt, boolean exempt) {
}

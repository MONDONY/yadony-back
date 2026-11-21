package com.yadony.api.auth.dto;

import java.util.List;

/**
 * Réponse de {@code GET /auth/me/username/availability}.
 *
 * @param available l'identifiant peut être pris par l'appelant
 * @param reason {@code null} si disponible, sinon {@code INVALID_FORMAT}, {@code RESERVED},
 *        {@code TAKEN}, {@code RECENTLY_RELEASED} ou {@code UNCHANGED} (c'est déjà le sien)
 * @param suggestions jusqu'à trois alternatives libres ; vide hors {@code TAKEN} et
 *        {@code RECENTLY_RELEASED}
 */
public record UsernameAvailabilityResponse(boolean available, String reason, List<String> suggestions) {
}

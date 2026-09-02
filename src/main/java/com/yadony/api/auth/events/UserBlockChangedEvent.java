package com.yadony.api.auth.events;

import java.util.UUID;

/**
 * Une relation de blocage vient d'être créée ({@code blocked = true}) ou levée.
 *
 * <p>Publié par {@code BlockService} pour que les autres packages réagissent sans
 * injection croisée : typiquement purger un cache de lecture clé par viewer, qui
 * servirait sinon le contenu du compte bloqué jusqu'à son expiration.
 */
public record UserBlockChangedEvent(UUID blockerId, UUID blockedId, boolean blocked) {
}

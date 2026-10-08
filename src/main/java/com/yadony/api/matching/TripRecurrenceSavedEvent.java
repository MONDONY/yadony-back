package com.yadony.api.matching;

import java.util.UUID;

/**
 * Récurrence active créée ou modifiée : ses trajets dus sont à générer une fois la
 * transaction commitée (voir {@link TripRecurrenceGenerationListener}).
 */
record TripRecurrenceSavedEvent(UUID recurrenceId) {
}

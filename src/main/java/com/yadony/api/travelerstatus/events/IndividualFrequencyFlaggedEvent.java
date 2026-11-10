package com.yadony.api.travelerstatus.events;

import java.util.UUID;

/**
 * Publié une seule fois, à la création d'un signalement « particulier à confirmer » (lot 4). Le paquet
 * {@code notifications/} l'écoute pour poser au voyageur la question neutre « Confirmez votre statut ».
 */
public record IndividualFrequencyFlaggedEvent(UUID userId, UUID flagId) {
}

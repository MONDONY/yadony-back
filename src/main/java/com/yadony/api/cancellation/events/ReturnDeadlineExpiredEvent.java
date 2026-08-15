package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * Publié par {@code ReturnDeadlineScheduler} quand le délai de 3 jours de retour
 * d'un colis annulé après remise est dépassé sans confirmation. NE suspend PAS :
 * lève une alerte admin (la suspension de publication est décidée par l'admin).
 */
public record ReturnDeadlineExpiredEvent(UUID bidId, UUID senderId, UUID travelerId) {
}

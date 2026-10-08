package com.yadony.api.cancellation.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Publié quand l'EXPÉDITEUR annule lui-même un colis déjà remis au voyageur
 * ({@code CancellationService#cancelAfterHandover}) : le voyageur doit rendre le colis
 * avant {@code returnDeadline} et saisir le code de retour que l'expéditeur lui donnera.
 * Sans cet événement, le voyageur n'apprenait l'annulation qu'en rouvrant le colis (PR #447).
 * Écouté par {@code NotificationDispatcher} (notification {@code PARCEL_RETURN_TO_SENDER}).
 */
public record ParcelReturnToSenderRequestedEvent(UUID bidId, UUID travelerId, UUID senderId,
                                                 LocalDate returnDeadline) {
}

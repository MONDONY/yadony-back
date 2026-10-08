package com.yadony.api.cancellation.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L'expéditeur a fixé un nouveau rendez-vous de livraison pendant la garde d'un colis dont le
 * destinataire était absent (FLUTTER-E2). Écouté par {@code NotificationDispatcher} pour
 * prévenir le voyageur.
 */
public record DeliveryRetryAppointmentSetEvent(UUID bidId, UUID senderId, UUID travelerId,
                                               OffsetDateTime appointmentAt) {
}

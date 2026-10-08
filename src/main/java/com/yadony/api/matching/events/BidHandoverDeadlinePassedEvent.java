package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Une demande non engagée a été annulée parce que la date limite de dépôt du trajet est
 * passée (FLUTTER-GA). Événement de notification uniquement : le remboursement passe par
 * {@link BidExpiredOnDepartureEvent} ou par les expirations de paiement existantes.
 *
 * @param refunded       un paiement était en séquestre et va être rendu intégralement
 * @param notifyTraveler le voyageur connaissait la demande (une demande carte jamais payée
 *                       ni négociée ne lui a jamais été présentée)
 */
public record BidHandoverDeadlinePassedEvent(
        UUID bidId,
        UUID announcementId,
        UUID senderId,
        UUID travelerId,
        boolean refunded,
        boolean notifyTraveler
) {}

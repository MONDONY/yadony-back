package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * Le voyageur a déclaré l'expéditeur absent à la remise (HANDOVER, SENDER_NO_SHOW,
 * statut PENDING_CONFIRMATION). Écouté après commit par
 * {@code notifications/NoShowNotificationListener} : l'expéditeur est prévenu qu'il
 * dispose de {@code contestationHours} heures pour contester.
 */
public record SenderNoShowReportedEvent(
        UUID bidId,
        UUID cancellationId,
        UUID senderId,
        UUID travelerId,
        int contestationHours
) {
}

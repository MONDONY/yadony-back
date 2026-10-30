package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * La déclaration « expéditeur absent » encore en attente (PENDING_CONFIRMATION) a été levée par
 * le scan de remise du voyageur : l'expéditeur est finalement venu. Publiée dans la transaction du
 * scan, écoutée après commit par {@code notifications/NoShowNotificationListener} (l'expéditeur est
 * prévenu qu'il n'a plus rien à contester). Un scan refusé ensuite (autre porte, erreur) annule
 * tout : rien n'est envoyé.
 */
public record SenderNoShowWithdrawnEvent(
        UUID bidId,
        UUID cancellationId,
        UUID senderId
) {
}

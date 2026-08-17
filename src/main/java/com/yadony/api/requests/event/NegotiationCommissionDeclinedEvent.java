package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * Le voyageur renonce explicitement à un accord en espèces avant d'avoir réglé
 * la commission Yadony (thread {@code AWAITING_COMMISSION}). Rien n'a été
 * scellé — la demande n'est donc pas perdue, elle redevient disponible pour
 * d'autres voyageurs. Notifie l'expéditeur.
 */
public record NegotiationCommissionDeclinedEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID senderId,
    UUID travelerId
) {}

package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * Le voyageur n'a pas réglé la commission Yadony d'un accord en espèces dans
 * le délai imparti (thread {@code AWAITING_COMMISSION} devenu {@code EXPIRED}).
 * Rien n'a jamais été scellé — la demande redevient disponible pour d'autres
 * voyageurs. Les deux parties sont notifiées, chacune avec son propre message :
 * le voyageur a perdu la demande, l'expéditeur peut de nouveau la conclure.
 */
public record NegotiationCommissionExpiredEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID senderId,
    UUID travelerId
) {}

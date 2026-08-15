package com.yadony.api.cancellation.events;

import java.time.LocalDateTime;
import java.util.UUID;

/** Publié une seule fois, environ 24 h avant l'expiration du délai de retour. */
public record ReturnDeadlineWarningEvent(
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        LocalDateTime deadline) {
}

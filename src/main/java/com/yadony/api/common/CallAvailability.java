package com.yadony.api.common;

import java.util.UUID;

/** Un utilisateur peut-il lancer un appel audio dans cette conversation ? Implémentée par calls/. */
public interface CallAvailability {
    boolean canCall(UUID callerId, UUID conversationId);
}

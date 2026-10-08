package com.yadony.api.common;

import java.util.UUID;

/** Un utilisateur peut-il lancer un appel audio dans cette conversation ? Implémentée par calls/. */
public interface CallAvailability {
    boolean canCall(UUID callerId, UUID conversationId);

    /**
     * Variante pour la liste des conversations : {@code visibilityChecked} indique que
     * l'appelant a déjà écarté les fils dont la contrepartie est masquée (blocage), et
     * que ce contrôle peut être sauté.
     */
    default boolean canCall(UUID callerId, UUID conversationId, boolean visibilityChecked) {
        return canCall(callerId, conversationId);
    }
}

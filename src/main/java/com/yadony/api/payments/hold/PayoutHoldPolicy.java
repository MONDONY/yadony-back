package com.yadony.api.payments.hold;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Lecture du gel des versements : le seul point que consultent les chemins qui versent de
 * l'argent a un voyageur (livraison, force-release, relance mobile money).
 *
 * <p>Seuls les GAINS verses au beneficiaire sont retenus. Remboursements a l'expediteur,
 * remboursements de wallet et de commission ne consultent jamais cette politique.
 */
public interface PayoutHoldPolicy {

    /** Etat de gel du voyageur ; {@link PayoutHoldStatus#NONE} si {@code userId} est nul. */
    PayoutHoldStatus statusOf(UUID userId);

    default boolean isHeld(UUID userId) {
        return statusOf(userId).held();
    }

    /** Etats de gel de plusieurs voyageurs ; un voyageur non gele est absent de la carte. */
    Map<UUID, PayoutHoldStatus> statusesOf(Collection<UUID> userIds);
}

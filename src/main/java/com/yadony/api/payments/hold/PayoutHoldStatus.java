package com.yadony.api.payments.hold;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Etat de gel d'un voyageur.
 *
 * @param heldSince date du plus ancien gel actif ; {@code null} si non gele
 * @param reasons   motifs actifs, dans l'ordre de {@link PayoutHoldReason} ; vide si non gele
 */
public record PayoutHoldStatus(LocalDateTime heldSince, List<PayoutHoldReason> reasons) {

    public static final PayoutHoldStatus NONE = new PayoutHoldStatus(null, List.of());

    public boolean held() {
        return !reasons.isEmpty();
    }

    /** Motif principal (le premier dans l'ordre de l'enum) ; {@code null} si non gele. */
    public PayoutHoldReason primaryReason() {
        return reasons.isEmpty() ? null : reasons.get(0);
    }
}

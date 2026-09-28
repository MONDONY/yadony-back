package com.yadony.api.payments.hold;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vue back-office du gel d'un voyageur : etat et nombre de paiements ESCROW retenus a la
 * livraison ({@code payout_held_at} pose). Les paiements retenus restent comptes apres la levee
 * du gel, tant qu'un administrateur ne les a pas liberes ou rembourses.
 */
public record PayoutHoldSummary(LocalDateTime heldSince, List<PayoutHoldReason> reasons, long heldPaymentsCount) {

    public static final PayoutHoldSummary NONE = new PayoutHoldSummary(null, List.of(), 0L);

    public PayoutHoldReason primaryReason() {
        return reasons == null || reasons.isEmpty() ? null : reasons.get(0);
    }
}

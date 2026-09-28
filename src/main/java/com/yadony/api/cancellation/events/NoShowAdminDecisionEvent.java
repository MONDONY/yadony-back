package com.yadony.api.cancellation.events;

import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.NoShowAdminDecision;

import java.util.List;
import java.util.UUID;

/**
 * Un administrateur a confirmé ou rejeté une déclaration de no-show
 * ({@code POST /admin/cancellations/{id}/confirm|reject}).
 *
 * <p>Écouté par {@code disputes/NoShowDecisionDisputeListener} (synchrone, même
 * transaction) qui ferme les litiges ouverts de {@code disputeTypesToClose}, et par
 * {@code notifications/NoShowNotificationListener} (après commit) qui prévient les deux
 * parties. Le motif interne de l'admin n'y figure volontairement pas : il reste dans
 * {@code cancellations.decision_reason} et l'audit_log.
 *
 * @param travelerId nul si l'annonce du bid est introuvable
 */
public record NoShowAdminDecisionEvent(
        UUID cancellationId,
        UUID bidId,
        CancellationScope scope,
        String reason,
        NoShowAdminDecision decision,
        UUID senderId,
        UUID travelerId,
        UUID adminId,
        List<String> disputeTypesToClose
) {
    public NoShowAdminDecisionEvent {
        disputeTypesToClose = disputeTypesToClose == null ? List.of() : List.copyOf(disputeTypesToClose);
    }
}

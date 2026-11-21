package com.yadony.api.cancellation.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Retenue d'annulation vue par l'administration, après un geste.
 *
 * @param status              DUE, RETAINED, PAYOUT_PENDING, PAID_OUT ou NOT_APPLICABLE
 * @param payoutPendingReason dernier motif de l'attente de versement, ou {@code null}
 */
public record AdminCancellationFeeResponse(
        UUID id,
        UUID bidId,
        String status,
        BigDecimal travelerNet,
        String currency,
        String payoutRef,
        String payoutPendingReason
) {
}

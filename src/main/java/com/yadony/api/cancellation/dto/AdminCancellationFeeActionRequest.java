package com.yadony.api.cancellation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /admin/cancellation-fees/bids/{bidId}/waive} et
 * {@code POST /admin/cancellation-fees/{feeId}/retry-payout} : motif interne du geste, repris dans l'audit.
 */
public record AdminCancellationFeeActionRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}

package com.yadony.api.cancellation.dto;

import com.yadony.api.cancellation.AdminBidCancelReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /admin/bids/{id}/cancel}. La note est interne (audit) ; elle devient
 * obligatoire (10 caractères au moins) quand le motif est {@link AdminBidCancelReason#OTHER}.
 */
public record AdminBidCancelRequest(
        @NotNull AdminBidCancelReason reason,
        @Size(max = 1000) String note
) {
}

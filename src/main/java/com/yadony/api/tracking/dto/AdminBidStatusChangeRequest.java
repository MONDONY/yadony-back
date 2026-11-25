package com.yadony.api.tracking.dto;

import com.yadony.api.tracking.AdminForcedBidStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /admin/bids/{id}/status}. Le motif est obligatoire et conservé dans
 * l'audit : il doit citer la trace écrite qui justifie le changement.
 */
public record AdminBidStatusChangeRequest(
        @NotNull AdminForcedBidStatus status,
        @NotBlank @Size(min = 10, max = 1000) String reason
) {
}

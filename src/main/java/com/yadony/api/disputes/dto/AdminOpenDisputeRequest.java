package com.yadony.api.disputes.dto;

import com.yadony.api.disputes.AdminDisputeReason;
import com.yadony.api.disputes.DisputeParty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corps de {@code POST /admin/bids/{id}/disputes}. */
public record AdminOpenDisputeRequest(
        @NotNull DisputeParty openedOnBehalfOf,
        @NotNull AdminDisputeReason reason,
        @NotBlank @Size(min = 10, max = 2000) String description
) {
}

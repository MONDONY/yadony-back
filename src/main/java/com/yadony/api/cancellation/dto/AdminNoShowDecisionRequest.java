package com.yadony.api.cancellation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps de {@code POST /admin/cancellations/{id}/confirm|reject} : motif interne de la décision. */
public record AdminNoShowDecisionRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}

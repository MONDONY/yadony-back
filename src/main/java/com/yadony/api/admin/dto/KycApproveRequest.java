package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Validation manuelle d'une identite : le motif interne est obligatoire (audit, decision_reason). */
public record KycApproveRequest(
        @NotBlank @Size(min = 10, max = 1000) String reason
) {}

package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Revocation d'une identite verifiee : geste lourd, le motif doit etre circonstancie. */
public record KycRevokeRequest(
        @NotBlank String code,
        @NotBlank @Size(min = 20, max = 1000) String reason
) {}

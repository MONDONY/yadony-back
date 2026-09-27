package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Refus manuel. {@code code} appartient au catalogue ferme {@code KycRejectionCodes} (400
 * {@code kyc-reject-code-invalid} sinon) ; seul lui est montre a l'utilisateur.
 */
public record KycRejectRequest(
        @NotBlank String code,
        @NotBlank @Size(min = 10, max = 1000) String reason
) {}

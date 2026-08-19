package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lot C — réinitialisation du KYC d'un utilisateur par un administrateur. */
public record KycResetRequest(
        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 500) String reason
) {}

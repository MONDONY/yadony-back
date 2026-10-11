package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Annulation d'une réservation de versement carte : motif obligatoire, audité. */
public record ReleaseIntentCancelRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
    public String trimmedReason() {
        return reason == null ? null : reason.trim();
    }
}

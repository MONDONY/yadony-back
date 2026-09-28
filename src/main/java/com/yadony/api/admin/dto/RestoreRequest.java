package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motif d'une restauration ou d'une annulation par un administrateur. Il part tel quel dans
 * {@code audit_log}, immuable : borne haute pour limiter ce qu'on y grave, borne basse pour
 * qu'il explique vraiment le geste.
 */
public record RestoreRequest(
        @NotBlank(message = "Le motif est obligatoire")
        @Size(min = 10, max = 500, message = "Le motif doit contenir entre 10 et 500 caractères")
        String reason
) {
    /** Motif débarrassé de ses espaces de bord, tel qu'il est audité. */
    public String normalizedReason() {
        return reason == null ? "" : reason.trim();
    }
}

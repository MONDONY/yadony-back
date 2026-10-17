package com.yadony.api.gppro.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Motif d'un refus de demande ou d'un retrait du badge ; l'utilisateur le lit. */
public record GpProDecisionRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {
}

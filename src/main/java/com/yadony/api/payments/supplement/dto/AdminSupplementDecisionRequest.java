package com.yadony.api.payments.supplement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Décision admin sur un supplément : la raison est obligatoire, elle va au journal d'audit. */
public record AdminSupplementDecisionRequest(@NotBlank @Size(max = 500) String reason) {
}

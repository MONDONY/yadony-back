package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lot C — exécution administrateur d'une suppression RGPD. Irréversible. */
public record GdprExecuteRequest(
        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 500) String reason
) {}

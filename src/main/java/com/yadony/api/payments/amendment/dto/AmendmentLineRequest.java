package com.yadony.api.payments.amendment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Un article de la grille du trajet ajouté par l'expéditeur, avec sa quantité. */
public record AmendmentLineRequest(
        @NotNull UUID gridItemId,
        @Min(1) @Max(20) int quantity) {
}

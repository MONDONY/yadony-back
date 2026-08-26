package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Motif d'une suppression décidée par l'administrateur.
 *
 * <p>Le motif catalogué sert aux statistiques de modération, le motif libre à la traçabilité :
 * les deux sont exigés, une suppression sans justification écrite ne doit pas être possible.
 */
public record AdminDeleteUserRequest(
        @NotBlank(message = "Le motif catalogué est obligatoire")
        @Pattern(regexp = "FRAUD|ABUSE|TEST_ACCOUNT|DUPLICATE|USER_REQUEST_OFFLINE|OTHER",
                 message = "Motif catalogué inconnu")
        String reasonCode,

        @NotBlank(message = "Le motif détaillé est obligatoire")
        String reason
) {}

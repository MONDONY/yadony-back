package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Motif d'une suppression décidée par l'administrateur.
 *
 * <p>Le motif catalogué sert aux statistiques de modération, le motif libre à la traçabilité :
 * les deux sont exigés, une suppression sans justification écrite ne doit pas être possible.
 *
 * <p>Le motif libre est volontairement limité en taille : il est stocké dans une colonne JSONB
 * protégée par un trigger d'immuabilité et ne peut pas être corrigé après coup. Une borne
 * raisonnable force la concision et limite l'exposition accidentelle de données personnelles.
 */
public record AdminDeleteUserRequest(
        @NotBlank(message = "Le motif catalogué est obligatoire")
        @Pattern(regexp = "FRAUD|ABUSE|TEST_ACCOUNT|DUPLICATE|USER_REQUEST_OFFLINE|OTHER",
                 message = "Motif catalogué inconnu")
        String reasonCode,

        @NotBlank(message = "Le motif détaillé est obligatoire")
        @Size(max = 500, message = "Le motif détaillé ne peut pas dépasser 500 caractères")
        String reason
) {}

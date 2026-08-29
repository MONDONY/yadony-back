package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motif d'un accès PRO offert par un administrateur.
 *
 * <p>Obligatoire : un accès gratuit est un geste commercial qui doit rester explicable.
 * Borné à 500 caractères, ce qui correspond à la colonne {@code admin_grant_reason}.
 */
public record ProGrantRequest(
        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 500, message = "Le motif ne peut pas dépasser 500 caractères")
        String reason
) {}

package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Coupure de la messagerie d'un utilisateur.
 * {@code durationHours} null = coupure indéfinie jusqu'à levée manuelle.
 */
public record MuteMessagingRequest(
        @Positive(message = "La durée doit être positive")
        Integer durationHours,
        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 500) String reason
) {}

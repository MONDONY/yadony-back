package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Message et heure de fin facultatifs ; null = texte par défaut de l'application. */
public record AdminMaintenanceUpdateRequest(
        @NotNull Boolean enabled,
        @Size(max = 300) String message,
        Instant estimatedEndAt) {}

package com.yadony.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Longueurs alignees sur les colonnes de admin_broadcasts (120 / 500). */
public record BroadcastRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 500) String body,
        @NotNull @Valid BroadcastTargetRequest target) {
}

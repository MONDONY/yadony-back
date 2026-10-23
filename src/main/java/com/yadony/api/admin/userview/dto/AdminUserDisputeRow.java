package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/** Une ligne de l'onglet « Litiges » : {@code role} vaut {@code SENDER}, {@code TRAVELER} ou {@code REPORTER}. */
public record AdminUserDisputeRow(
        UUID id,
        UUID bidId,
        String type,
        String status,
        String role,
        Instant createdAt,
        Instant resolvedAt
) {}

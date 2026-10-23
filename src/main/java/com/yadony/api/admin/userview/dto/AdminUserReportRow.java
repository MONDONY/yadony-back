package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/** Signalement lié à un utilisateur ; {@code direction} vaut {@code RECEIVED} ou {@code SENT}. */
public record AdminUserReportRow(
        UUID id,
        String direction,
        String targetType,
        String reason,
        String status,
        Instant createdAt
) {}

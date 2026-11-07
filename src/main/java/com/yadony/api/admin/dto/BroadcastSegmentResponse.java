package com.yadony.api.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

/** {@code recipientCount} vaut -1 quand il n'est pas calculé (liste). */
public record BroadcastSegmentResponse(
        UUID id,
        String name,
        String description,
        JsonNode rule,
        long recipientCount,
        LocalDateTime updatedAt) {
}

package com.yadony.api.admin.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.AdminBroadcastEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/** {@code audienceRule} : objet JSON (règle copiée à l'envoi), {@code null} pour un envoi ancien. */
public record AdminBroadcastResponse(
        UUID id,
        String title,
        String body,
        String targetType,
        String targetOrigin,
        String targetDestination,
        UUID targetUserId,
        int recipientCount,
        UUID adminId,
        LocalDateTime createdAt,
        JsonNode audienceRule,
        UUID segmentId,
        String segmentName) {

    public static AdminBroadcastResponse from(AdminBroadcastEntity entity, ObjectMapper mapper) {
        return new AdminBroadcastResponse(
                entity.getId(), entity.getTitle(), entity.getBody(),
                entity.getTargetType().name(), entity.getTargetOrigin(), entity.getTargetDestination(),
                entity.getTargetUserId(), entity.getRecipientCount(), entity.getAdminId(),
                entity.getCreatedAt(), readRule(entity.getAudienceRule(), mapper),
                entity.getSegmentId(), entity.getSegmentName());
    }

    private static JsonNode readRule(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Règle d'historique illisible", e);
        }
    }
}

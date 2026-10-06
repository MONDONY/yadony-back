package com.yadony.api.admin.dto;

import com.yadony.api.admin.AdminAlertEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminAlertResponse(
        UUID id,
        String type,
        String severity,
        String detail,
        Map<String, Object> payload,
        boolean resolved,
        OffsetDateTime resolvedAt,
        LocalDateTime createdAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AdminAlertResponse from(AdminAlertEntity e) {
        return new AdminAlertResponse(
                e.getId(),
                e.getType(),
                e.getSeverity(),
                e.getDetail(),
                parsePayload(e.getPayload()),
                e.isResolved(),
                e.getResolvedAt(),
                e.getCreatedAt()
        );
    }

    /** La colonne payload est du TEXT JSON ; le front attend un objet. */
    private static Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", raw);
        }
    }
}

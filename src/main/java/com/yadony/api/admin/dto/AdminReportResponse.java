package com.yadony.api.admin.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdminReportResponse(
        UUID id,
        String targetType,
        UUID targetId,
        String targetLabel,
        String reporterName,
        String reason,
        String description,
        String status,
        String actionTaken,
        String resolutionNote,
        OffsetDateTime resolvedAt,
        LocalDateTime createdAt,
        List<String> photoUrls
) {
}

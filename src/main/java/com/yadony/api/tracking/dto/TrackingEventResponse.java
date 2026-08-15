package com.yadony.api.tracking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TrackingEventResponse(
        UUID id,
        UUID bidId,
        String eventType,
        LocalDateTime scannedAt,
        BigDecimal gpsLat,
        BigDecimal gpsLon,
        String gpsLabel,
        String photoUrl,
        LocalDateTime offlineTimestamp,
        LocalDateTime createdAt
) {}

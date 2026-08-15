package com.yadony.api.tracking.dto;

import com.yadony.api.tracking.TrackingEventType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record QrScanRequest(
        @NotNull UUID bidId,
        @NotNull TrackingEventType eventType,
        BigDecimal gpsLat,
        BigDecimal gpsLon,
        String gpsLabel,
        String photoUrl,
        LocalDateTime offlineTimestamp
) {}

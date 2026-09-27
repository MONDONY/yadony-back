package com.yadony.api.tracking.dto;

import java.time.LocalDateTime;

/**
 * @param scanMethod {@code QR}, {@code MANUAL} ou null (provenance inconnue)
 */
public record TripScanHistoryEntryDto(
        String donNumber,
        String recipientName,
        String eventType,
        LocalDateTime scannedAt,
        String scanMethod
) {}

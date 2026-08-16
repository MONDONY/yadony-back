package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBidListItemResponse(
    UUID id,
    String status,
    UUID announcementId,
    String senderName,
    String travelerName,
    String corridor,
    BigDecimal weightKg,
    BigDecimal netEur,
    String paymentMethod,
    LocalDateTime createdAt,
    // Statut du règlement de la commission Yadony (PENDING, REQUIRES_3DS, CHARGED,
    // FAILED, REFUNDED, REFUND_FAILED, ou null si non applicable) — expose une
    // commission cash jamais réglée qui resterait sinon invisible côté exploitation.
    String commissionStatus
) {}

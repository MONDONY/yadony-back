package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBidDetailResponse(
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
    String contentCategory,
    String recipientName,
    String trackingNumber,
    BigDecimal commissionRate,
    String refusalReason,
    /** Devise de netEur, celle de l'annonce (code ISO en majuscules). */
    String currency
) {}

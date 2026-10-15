package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminTopupCodeResponse(UUID id, String code, BigDecimal amount, String currency,
                                     int maxRedemptions, int redeemedCount, int remaining,
                                     LocalDateTime expiresAt, String displayStatus, String note,
                                     LocalDateTime createdAt, UUID createdBy, String createdByEmail,
                                     LocalDateTime revokedAt) {}

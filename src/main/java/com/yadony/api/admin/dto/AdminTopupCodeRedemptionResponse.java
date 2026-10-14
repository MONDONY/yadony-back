package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminTopupCodeRedemptionResponse(UUID id, UUID userId, String userName, BigDecimal amount,
                                               String currency, LocalDateTime createdAt) {}

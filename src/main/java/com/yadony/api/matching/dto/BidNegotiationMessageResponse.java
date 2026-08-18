package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Message sortant d'un fil de négociation de trajet. */
public record BidNegotiationMessageResponse(
        UUID id, String kind, UUID authorId,
        BigDecimal proposedGrossEur, String body, LocalDateTime createdAt) {}

package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.List;

/** Les livraisons d'une devise et leur sous-total, dans cette devise. */
public record RevenueGroupDto(
        String currency,
        BigDecimal total,
        long deliveries,
        List<RevenueItemDto> items
) {}

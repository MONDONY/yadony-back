package com.yadony.api.matching.dto;

import java.util.List;

public record ProAnalyticsResponse(
        String period,
        List<KpiDto> kpis,
        List<TransactionRowDto> transactions
) {
    public record KpiDto(
            String id,
            String label,
            String value,
            String trend,
            String trendValue
    ) {}

    /** Montants en unités MINEURES de {@code currency} (centimes EUR, unité pleine XOF). */
    public record TransactionRowDto(
            String tripId,
            String corridor,
            String departureDate,
            int parcelCount,
            long grossRevenue,
            long commission,
            long netRevenue,
            String currency
    ) {}
}

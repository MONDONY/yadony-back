package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.List;

/** Feuille « Kg vendus » du hub Activités : total de la période, puis trajet par trajet. */
public record KgSoldDetailsDto(
        String period,
        BigDecimal totalKg,
        long parcels,
        List<KgSoldTripDto> trips
) {
    public static KgSoldDetailsDto of(String period, List<KgSoldTripRow> rows) {
        BigDecimal totalKg = rows.stream()
                .map(KgSoldTripRow::kg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long parcels = rows.stream().mapToLong(KgSoldTripRow::parcels).sum();
        return new KgSoldDetailsDto(period, totalKg, parcels,
                rows.stream().map(KgSoldTripDto::from).toList());
    }
}

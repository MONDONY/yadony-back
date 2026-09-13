package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Poids livré sur un trajet, avec le nombre de colis, sur la période. */
public record KgSoldTripDto(
        UUID tripId,
        String departureCity,
        String arrivalCity,
        LocalDate date,
        long parcels,
        BigDecimal kg
) {
    public static KgSoldTripDto from(KgSoldTripRow row) {
        return new KgSoldTripDto(row.tripId(), row.departureCity(), row.arrivalCity(),
                row.departureDate(), row.parcels(), row.kg());
    }
}

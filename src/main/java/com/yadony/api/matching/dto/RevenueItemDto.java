package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une livraison payée, dans la devise de son groupe. {@code date} est la date de
 * départ du trajet ; sans trajet lié (paiement de fil non rattaché), la date du
 * paiement. {@code tripId} et {@code weightKg} peuvent être nuls.
 */
public record RevenueItemDto(
        UUID tripId,
        String departureCity,
        String arrivalCity,
        LocalDate date,
        BigDecimal weightKg,
        RevenueRail rail,
        BigDecimal amount
) {
    public static RevenueItemDto fromPayment(PaymentLineRow row) {
        LocalDate date = row.departureDate() != null
                ? row.departureDate()
                : row.paidAt().toLocalDate();
        return new RevenueItemDto(row.tripId(), row.departureCity(), row.arrivalCity(), date,
                row.weightKg(), RevenueRail.fromPaymentRail(row.rail()), row.amount());
    }

    public static RevenueItemDto fromCash(CashLineRow row) {
        return new RevenueItemDto(row.tripId(), row.departureCity(), row.arrivalCity(),
                row.departureDate(), row.weightKg(), RevenueRail.CASH, row.amount());
    }

    RevenueItemDto withAmount(BigDecimal scaled) {
        return new RevenueItemDto(tripId, departureCity, arrivalCity, date, weightKg, rail, scaled);
    }
}

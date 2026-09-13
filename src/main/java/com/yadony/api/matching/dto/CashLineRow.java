package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Un bid livré réglé en espèces, vu comme une ligne de revenu net du voyageur. */
public record CashLineRow(
        UUID tripId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal weightKg,
        String currency,
        BigDecimal amount
) {}

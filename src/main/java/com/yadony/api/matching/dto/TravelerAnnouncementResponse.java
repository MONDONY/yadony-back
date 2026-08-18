package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TravelerAnnouncementResponse(
    UUID id,
    String departureCity,
    String arrivalCity,
    LocalDate departureDate,
    BigDecimal pricePerKg,
    BigDecimal availableKg,
    String status,
    String currency,
    /** Le voyageur accepte les propositions de prix. */
    boolean negotiable
) {}

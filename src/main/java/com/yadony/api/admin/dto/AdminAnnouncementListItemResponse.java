package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminAnnouncementListItemResponse(
    UUID id,
    String status,
    String travelerName,
    String corridor,
    LocalDate departureDate,
    BigDecimal availableKg,
    BigDecimal pricePerKg,
    /** Devise de pricePerKg (code ISO en majuscules). */
    String currency
) {}

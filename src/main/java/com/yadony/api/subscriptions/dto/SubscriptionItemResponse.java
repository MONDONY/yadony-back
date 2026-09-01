package com.yadony.api.subscriptions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriptionItemResponse(
    UUID travelerId,
    String travelerName,
    String avatarUrl,
    boolean isProAccount,
    BigDecimal averageRating,
    long ongoingTripsCount,
    boolean pushEnabled,
    boolean hasNew,
    LastAnnouncement lastAnnouncement
) {
    public record LastAnnouncement(
        UUID announcementId,
        String departureCity,
        String arrivalCity,
        BigDecimal pricePerKg,
        String currency,
        LocalDate departureDate,
        LocalDateTime publishedAt
    ) {}
}

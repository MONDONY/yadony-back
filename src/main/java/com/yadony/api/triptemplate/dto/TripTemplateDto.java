package com.yadony.api.triptemplate.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.PaymentMethod;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record TripTemplateDto(
    UUID id,
    String label,
    String emoji,
    String departureCity,
    Double departureLat,
    Double departureLng,
    String arrivalCity,
    Double arrivalLat,
    Double arrivalLng,
    String transportMode,
    String capacityUnit,
    Integer availableKg,
    Double pricePerKg,
    List<String> acceptedCategories,
    boolean cashAccepted,
    @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
    String currency,
    String pricingMode,
    Set<PaymentMethod> acceptedPaymentMethods,
    boolean negotiable,
    List<String> refusedTypes,
    String description,
    AddressDto pickupAddress,
    AddressDto deliveryAddress,
    @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
    Integer handoverLeadDays,
    String departureCountryCode,
    String arrivalCountryCode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

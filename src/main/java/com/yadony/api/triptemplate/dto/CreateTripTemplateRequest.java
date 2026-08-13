package com.yadony.api.triptemplate.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.List;

// Le plafond réel dépend de la devise du voyageur et est appliqué par
// TripTemplateService.assertPricePerKgWithinBounds : une annotation ne peut pas
// en dépendre. Seul un garde-fou anti-abus subsiste ici.
public record CreateTripTemplateRequest(
    @NotBlank @Size(max = 60)  String label,
    @Size(max = 8)             String emoji,
    @NotBlank @Size(max = 100) String departureCity,
    Double departureLat,
    Double departureLng,
    @NotBlank @Size(max = 100) String arrivalCity,
    Double arrivalLat,
    Double arrivalLng,
    @NotBlank @Size(max = 20)  String transportMode,
    @NotBlank @Size(max = 20)  String capacityUnit,
    @NotNull @Min(1) @Max(40)  Integer availableKg,
    @NotNull @Positive @DecimalMax("1000000.0") Double pricePerKg,
    List<String> acceptedCategories,
    boolean cashAccepted,
    @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime
) {}

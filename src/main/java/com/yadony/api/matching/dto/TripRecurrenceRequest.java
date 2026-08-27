package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yadony.api.matching.PricingMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record TripRecurrenceRequest(
        UUID sourceTemplateId,

        @NotBlank @Size(max = 100) String departureCity,
        @NotBlank @Size(max = 100) String arrivalCity,
        @NotBlank @Size(max = 20)  String transportMode,
        @NotBlank @Size(max = 20)  String capacityUnit,

        @NotNull @DecimalMin("1.0") @DecimalMax("40.0") Double availableKg,
        @NotNull @Positive @DecimalMax("500.0") Double pricePerKg,

        List<String> acceptedCategories,
        List<String> refusedCategories,

        @Size(max = 500) String description,

        @Valid @NotNull AddressDto pickupAddress,
        @Valid @NotNull AddressDto deliveryAddress,

        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
        @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,

        boolean cashAccepted,

        @NotBlank @Pattern(regexp = "[01]{7}", message = "weekdays doit être 7 caractères 0/1 (Lun..Dim)")
        String weekdays,

        @Min(1) @Max(60) Integer horizonDays,

        LocalDate startDate,
        LocalDate endDate,

        @Min(1) @Max(4) Integer weekInterval,
        Integer publicationLeadDays,
        @Min(0) @Max(3) Integer handoverLeadDays,

        PricingMode pricingMode,
        Boolean negotiable,
        @Pattern(regexp = "[A-Z]{3}") String currency,

        boolean active
) {
    public TripRecurrenceRequest(
            UUID sourceTemplateId,
            String departureCity,
            String arrivalCity,
            String transportMode,
            String capacityUnit,
            Double availableKg,
            Double pricePerKg,
            List<String> acceptedCategories,
            AddressDto pickupAddress,
            AddressDto deliveryAddress,
            LocalTime departureTime,
            LocalTime arrivalTime,
            boolean cashAccepted,
            String weekdays,
            Integer horizonDays,
            boolean active
    ) {
        this(sourceTemplateId, departureCity, arrivalCity, transportMode, capacityUnit,
                availableKg, pricePerKg, acceptedCategories, null, null,
                pickupAddress, deliveryAddress, departureTime, arrivalTime, cashAccepted,
                weekdays, horizonDays, null, null, null, null, null,
                null, null, null, active);
    }

    @JsonIgnore
    @AssertTrue(message = "La date de fin doit être postérieure ou égale à la date de début")
    public boolean isPeriodValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @JsonIgnore
    @AssertTrue(message = "Choisissez un délai de publication de 7, 14, 21 ou 30 jours")
    public boolean isPublicationLeadDaysValid() {
        return publicationLeadDays == null
                || publicationLeadDays == 7
                || publicationLeadDays == 14
                || publicationLeadDays == 21
                || publicationLeadDays == 30;
    }

    @JsonIgnore
    @AssertTrue(message = "Sélectionnez au moins un jour de départ")
    public boolean isWeekdaySelected() {
        return weekdays != null && weekdays.contains("1");
    }
}

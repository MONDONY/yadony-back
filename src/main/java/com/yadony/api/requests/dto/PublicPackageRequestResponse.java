package com.yadony.api.requests.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.ParcelSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PublicPackageRequestResponse(
        UUID id,
        String departureCity,
        String arrivalCity,
        BigDecimal departureLat,
        BigDecimal departureLng,
        BigDecimal arrivalLat,
        BigDecimal arrivalLng,
        LocalDate desiredDate,
        int dateToleranceDays,
        BigDecimal weightKg,
        ParcelSize parcelSize,
        TransportMode transportMode,
        String contentCategory,
        String description,
        BigDecimal targetPriceEur,
        boolean negotiable,
        String photoUrl,
        String senderDisplayName,
        List<PackageRequestPhotoResponse> photos,
        boolean urgent,
        String currency
) {}

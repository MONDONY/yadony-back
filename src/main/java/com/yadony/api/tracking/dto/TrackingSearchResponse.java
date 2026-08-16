package com.yadony.api.tracking.dto;

import java.util.UUID;

public record TrackingSearchResponse(
        String trackingNumber,
        UUID bidId,
        String departureCity,
        String arrivalCity,
        String currentStep,
        String stepLabel,
        String paymentStatus,
        String arrivalInstructions
) {}

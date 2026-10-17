package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProTravelFrequency;

import java.time.LocalDateTime;

/** {@code state} : NONE, AWAITING_KYC, PENDING, ACTIVE, REJECTED ou REVOKED. */
public record GpProStatusResponse(String state, GpProTravelFrequency travelFrequency, String commercialName,
                                  int documentCount, String reason, LocalDateTime decidedAt) {
}

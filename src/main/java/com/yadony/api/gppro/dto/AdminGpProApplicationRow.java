package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProApplicationSource;
import com.yadony.api.gppro.GpProTravelFrequency;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminGpProApplicationRow(UUID applicationId, UUID userId, String displayName,
                                       GpProApplicationSource source, GpProTravelFrequency travelFrequency,
                                       int documentCount, LocalDateTime createdAt) {
}

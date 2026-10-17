package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProApplicationSource;
import com.yadony.api.gppro.GpProTravelFrequency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record GpProApplyRequest(
        @NotNull GpProTravelFrequency travelFrequency,
        @Size(max = 80) String commercialName,
        @Size(max = 4) List<UUID> documentIds,
        @NotNull GpProApplicationSource source) {
}

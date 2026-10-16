package com.yadony.api.assistant.provider.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProviderRequest(
        @NotBlank @Size(max = 60) String name,
        @NotBlank String baseUrl,
        @NotBlank @Size(max = 120) String model,
        @NotBlank @Size(min = 8, max = 500) String apiKey,
        boolean supportsJsonMode,
        @Min(1) @Max(1_000_000) Integer dailyRequestLimit,
        boolean enabled) {
}

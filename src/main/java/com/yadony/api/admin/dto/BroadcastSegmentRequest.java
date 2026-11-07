package com.yadony.api.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BroadcastSegmentRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 300) String description,
        @NotNull JsonNode rule) {
}

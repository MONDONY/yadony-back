package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank(message = "{validation.device.name.required}")
        @Size(max = 255)
        String deviceName,

        @NotBlank
        @Pattern(regexp = "ios|android|web", message = "{validation.device.platform.invalid}")
        String platform
) {}

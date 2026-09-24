package com.yadony.api.matching.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddressDto(
        @NotBlank(message = "{validation.address.label.required}")
        String label,

        @NotNull(message = "{validation.address.lat.required}")
        @DecimalMin(value = "-90.0", message = "{validation.address.lat.min}")
        @DecimalMax(value = "90.0",  message = "{validation.address.lat.max}")
        Double lat,

        @NotNull(message = "{validation.address.lng.required}")
        @DecimalMin(value = "-180.0", message = "{validation.address.lng.min}")
        @DecimalMax(value = "180.0",  message = "{validation.address.lng.max}")
        Double lng
) {}

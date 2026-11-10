package com.yadony.api.videomodal.dto;

import jakarta.validation.constraints.NotNull;

public record VideoModalActiveRequest(@NotNull Boolean active) {
}

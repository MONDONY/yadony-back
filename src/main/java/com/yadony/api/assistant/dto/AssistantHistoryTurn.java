package com.yadony.api.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssistantHistoryTurn(@NotBlank String role, @NotNull String text) {
}

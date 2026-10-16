package com.yadony.api.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AssistantHistoryTurn(@NotBlank String role, @NotNull @Size(max = 4000) String text) {
}

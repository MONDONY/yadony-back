package com.yadony.api.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AssistantAskRequest(
        @NotBlank @Size(max = 500) String question,
        String locale,
        @Size(max = 50) List<@Valid AssistantHistoryTurn> history) {
}

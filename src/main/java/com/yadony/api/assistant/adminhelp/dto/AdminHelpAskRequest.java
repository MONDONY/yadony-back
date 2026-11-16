package com.yadony.api.assistant.adminhelp.dto;

import com.yadony.api.assistant.dto.AssistantHistoryTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminHelpAskRequest(@NotBlank @Size(max = 500) String question,
                                  @Size(max = 40) List<@Valid AssistantHistoryTurn> history) {
}

package com.yadony.api.assistant.formdraft.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FormDraftTurn(@NotNull @Pattern(regexp = "user|assistant") String role,
                            @NotNull @Size(max = 1000) String text) {
}

package com.yadony.api.assistant.provider.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReorderProvidersRequest(@NotNull List<UUID> ids) {
}

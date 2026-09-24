package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BlockRequest(
        @NotNull(message = "{validation.block.user-id.required}")
        UUID blockedUserId
) {}

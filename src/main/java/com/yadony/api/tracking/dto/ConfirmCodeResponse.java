package com.yadony.api.tracking.dto;

import java.time.LocalDateTime;

public record ConfirmCodeResponse(
        String confirmationCode,
        LocalDateTime expiresAt,
        boolean publicPageVisible
) {}

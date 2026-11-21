package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Corps de {@code PUT /auth/me/username}. La forme exacte est validée par {@code UsernamePolicy}. */
public record ChangeUsernameRequest(@NotBlank String username) {
}

package com.yadony.api.auth.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(

    @Nullable
    @Pattern(
        regexp = "\\+[1-9]\\d{6,14}",
        message = "{validation.phone.e164}"
    )
    String phoneNumber,

    @Nullable
    @Email(message = "{validation.email.invalid}")
    String email,

    @Nullable
    @Size(max = 2, message = "{validation.roles.max}")
    Set<String> roles
) {}

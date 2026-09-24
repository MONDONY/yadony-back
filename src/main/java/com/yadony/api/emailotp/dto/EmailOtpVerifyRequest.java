package com.yadony.api.emailotp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailOtpVerifyRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}") String code
) {}

package com.yadony.api.smsotp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SmsOtpVerifyRequest(
    @NotBlank
    @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "{validation.phone.e164}")
    String phoneNumber,

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}")
    String code
) {}

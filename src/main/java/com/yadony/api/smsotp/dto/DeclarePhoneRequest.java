package com.yadony.api.smsotp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Numéro déclaré sans SMS, pour un compte dont l'indicatif est dans la liste des pays bloqués. */
public record DeclarePhoneRequest(
        @NotBlank
        @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "{validation.phone.e164}")
        String phoneNumber
) {}

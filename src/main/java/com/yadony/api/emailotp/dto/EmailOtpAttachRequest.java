package com.yadony.api.emailotp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Rattachement d'une adresse au compte authentifié. Le code OTP est exigé dans la
 * même requête que l'adresse : c'est ce qui prouve au serveur que l'appelant possède
 * bien la boîte mail. Sans cette preuve, n'importe quel porteur d'un token Firebase
 * valide pourrait s'attribuer l'adresse d'un tiers.
 */
public record EmailOtpAttachRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits-required}") String code
) {}

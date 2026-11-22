package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Numéro que l'administrateur a sous les yeux au moment de valider : la validation ne porte que
 * sur lui. Si le compte a redéclaré un autre numéro entre-temps, elle est refusée (409).
 */
public record VerifyDeclaredPhoneRequest(
        @NotBlank(message = "{validation.phone.e164}")
        @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "{validation.phone.e164}") String declaredPhone
) {
}

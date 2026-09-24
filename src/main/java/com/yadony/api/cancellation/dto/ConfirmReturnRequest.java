package com.yadony.api.cancellation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Corps de POST /cancellations/bids/{id}/confirm-return : le code de retour à 6 chiffres. */
public record ConfirmReturnRequest(
        @NotBlank(message = "{validation.return-code.required}")
        @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}")
        String returnCode
) {}

package com.yadony.api.tracking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Confirmation d'arrivée par le voyageur.
 *
 * @param photoUrl clé S3 interne de la photo de preuve (tracking/{bidId}/…),
 *                 optionnelle : les anciens clients n'envoient que le code
 */
public record ConfirmDeliveryRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}")
        String confirmationCode,
        @Size(max = 500)
        String photoUrl
) {
    public ConfirmDeliveryRequest(String confirmationCode) {
        this(confirmationCode, null);
    }
}

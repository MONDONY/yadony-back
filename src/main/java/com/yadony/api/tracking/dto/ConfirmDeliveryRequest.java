package com.yadony.api.tracking.dto;

import com.yadony.api.tracking.ScanMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Confirmation d'arrivée par le voyageur.
 *
 * @param photoUrl clé S3 interne de la photo de preuve (tracking/{bidId}/…),
 *                 optionnelle : les anciens clients n'envoient que le code
 * @param scanMethod provenance de l'identification du colis (QR ou numéro saisi),
 *                   facultative : null = inconnue
 */
public record ConfirmDeliveryRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}")
        String confirmationCode,
        @Size(max = 500)
        String photoUrl,
        ScanMethod scanMethod
) {
    public ConfirmDeliveryRequest(String confirmationCode) {
        this(confirmationCode, null, null);
    }

    public ConfirmDeliveryRequest(String confirmationCode, String photoUrl) {
        this(confirmationCode, photoUrl, null);
    }
}

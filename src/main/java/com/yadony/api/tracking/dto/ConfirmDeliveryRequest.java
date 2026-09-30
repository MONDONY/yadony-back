package com.yadony.api.tracking.dto;

import com.yadony.api.tracking.ScanMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Confirmation d'arrivée par le voyageur.
 *
 * @param photoUrl clé S3 interne de la photo de preuve (tracking/{bidId}/…),
 *                 optionnelle : les anciens clients n'envoient que le code
 * @param scanMethod provenance de l'identification du colis (QR ou numéro saisi),
 *                   facultative : null = inconnue
 * @param gpsLat, gpsLon, gpsLabel position relevée à l'arrivée, comme pour les autres
 *                   étapes ({@link QrScanRequest}). Facultatifs : les apps déjà installées
 *                   ne les envoient pas. Sans eux, l'arrivée n'avait jamais de lieu
 *                   (feedback FLUTTER-2A).
 */
public record ConfirmDeliveryRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}")
        String confirmationCode,
        @Size(max = 500)
        String photoUrl,
        ScanMethod scanMethod,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal gpsLat,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal gpsLon,
        @Size(max = 255) String gpsLabel
) {
    public ConfirmDeliveryRequest(String confirmationCode) {
        this(confirmationCode, null, null);
    }

    public ConfirmDeliveryRequest(String confirmationCode, String photoUrl) {
        this(confirmationCode, photoUrl, null);
    }

    public ConfirmDeliveryRequest(String confirmationCode, String photoUrl, ScanMethod scanMethod) {
        this(confirmationCode, photoUrl, scanMethod, null, null, null);
    }
}

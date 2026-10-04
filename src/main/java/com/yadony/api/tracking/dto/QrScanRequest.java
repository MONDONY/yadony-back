package com.yadony.api.tracking.dto;

import com.yadony.api.tracking.ScanMethod;
import com.yadony.api.tracking.TrackingEventType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bornes alignées sur les colonnes de {@code tracking_events} : sans elles, une clé photo
 * ou un libellé trop long partait jusqu'à l'insert et ressortait en 500 (contrainte de
 * longueur en base) au lieu d'un 422 explicite. {@code ConfirmDeliveryRequest} bornait déjà
 * sa photo à 500.
 *
 * <p>{@code scanMethod} est facultatif : les apps déjà installées ne l'envoient pas, l'étape
 * est alors enregistrée avec une provenance inconnue (null).
 *
 * <p>{@code trackingNumber} : numéro de suivi (« DON-… ») que seul l'expéditeur possède, saisi par
 * le voyageur à la remise du colis (étape DEPART) pour prouver qu'il l'a bien en main. Il n'est
 * demandé qu'au DEPART : les étapes suivantes l'ignorent.
 */
public record QrScanRequest(
        @NotNull UUID bidId,
        @NotNull TrackingEventType eventType,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal gpsLat,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal gpsLon,
        @Size(max = 255) String gpsLabel,
        @Size(max = 500) String photoUrl,
        LocalDateTime offlineTimestamp,
        ScanMethod scanMethod,
        @Size(max = 12) String trackingNumber
) {
    /** Contrat antérieur à la provenance QR / numéro. */
    public QrScanRequest(UUID bidId, TrackingEventType eventType, BigDecimal gpsLat, BigDecimal gpsLon,
                         String gpsLabel, String photoUrl, LocalDateTime offlineTimestamp) {
        this(bidId, eventType, gpsLat, gpsLon, gpsLabel, photoUrl, offlineTimestamp, null, null);
    }

    /** Contrat antérieur au numéro de suivi exigé à la remise. */
    public QrScanRequest(UUID bidId, TrackingEventType eventType, BigDecimal gpsLat, BigDecimal gpsLon,
                         String gpsLabel, String photoUrl, LocalDateTime offlineTimestamp,
                         ScanMethod scanMethod) {
        this(bidId, eventType, gpsLat, gpsLon, gpsLabel, photoUrl, offlineTimestamp, scanMethod, null);
    }
}

package com.yadony.api.tracking.dto;

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
 */
public record QrScanRequest(
        @NotNull UUID bidId,
        @NotNull TrackingEventType eventType,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal gpsLat,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal gpsLon,
        @Size(max = 255) String gpsLabel,
        @Size(max = 500) String photoUrl,
        LocalDateTime offlineTimestamp
) {}

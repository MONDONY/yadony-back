package com.yadony.api.tracking;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Remise qui vient d'être enregistrée (scan DEPART d'un colis ACCEPTED, étape insérée, colis passé
 * HANDED_OVER), passée aux {@link HandoverBlocker#onHandover} dans la transaction du scan, sous le
 * verrou du colis. Ce qui prouve la remise : l'étape, sa provenance, sa photo, l'heure hors ligne
 * et la position du scan.
 *
 * @param scanMethod       {@code QR} ou {@code MANUAL}, nul pour une ancienne app
 * @param photoKey         clé S3 de la photo de remise ({@code tracking/{bidId}/…}), nulle sans photo
 * @param offlineTimestamp heure du scan hors ligne (UTC), nulle pour un scan en ligne
 */
public record HandoverScan(
        UUID bidId,
        UUID travelerId,
        UUID trackingEventId,
        String scanMethod,
        String photoKey,
        LocalDateTime offlineTimestamp,
        BigDecimal gpsLat,
        BigDecimal gpsLon
) {
}

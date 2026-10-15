package com.yadony.api.tracking;

import java.util.List;

/** Ce que la page publique du QR montre d'un colis actif. Aucun champ sensible. */
public record ParcelScanView(
        String trackingNumber,
        String statusLabel,
        boolean delivered,
        String departureCity,
        String arrivalCity,
        String departureDate,
        String arrivalDate,
        List<String> categories,
        String description,
        String weight,
        String senderName,
        String recipientName,
        String travelerName,
        List<RecipientController.RecipientEventDto> events
) {
}

package com.yadony.api.payments.amendment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ce que l'expéditeur peut ajouter à son colis accepté.
 *
 * @param availableKg       kilos libres du trajet, {@code null} si la capacité est libre ({@code KG_FREE}).
 * @param pricePerKgDisplay prix au kilo tel que l'expéditeur le voit en recherche, {@code null} sans prix au kilo.
 * @param expiresAt         échéance de réponse du voyageur pour une demande créée maintenant (UTC).
 * @param minAmount         borne basse du net, convertie dans la devise du colis : validation serveur seulement.
 * @param maxAmount         borne haute du net, convertie dans la devise du colis : validation serveur seulement.
 */
public record AmendmentOptionsResponse(
        String currency,
        BigDecimal availableKg,
        BigDecimal pricePerKgDisplay,
        List<GridItemOption> gridItems,
        List<String> paymentMethods,
        LocalDateTime expiresAt,
        BigDecimal minAmount,
        BigDecimal maxAmount) {

    /** Article de la grille du trajet, au prix « affiché expéditeur ». */
    public record GridItemOption(UUID id, String label, BigDecimal unitPriceDisplay) {
    }
}

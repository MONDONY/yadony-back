package com.yadony.api.payments.amendment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ce que l'expéditeur peut ajouter à son colis accepté.
 *
 * @param availableKg       kilos libres du trajet, {@code null} si la capacité est libre ({@code KG_FREE}).
 * @param pricePerKgDisplay prix au kilo tel que l'expéditeur le voit en recherche, {@code null} sans prix au kilo.
 * @param expiresAt         échéance de réponse du voyageur pour une demande créée maintenant (UTC, décalage explicite).
 * @param minAmount         borne basse du net, convertie dans la devise du colis : validation serveur seulement.
 * @param maxAmount         borne haute du net, convertie dans la devise du colis : validation serveur seulement.
 * @param availableVolumeM3 volume libre d'un trajet vendu au m³ (ajout de volume possible), {@code null} sinon.
 *                          Toujours sérialisé.
 * @param pricePerM3Display prix au m³ tel que l'expéditeur le voit en recherche, {@code null} hors trajet au m³.
 *                          Toujours sérialisé.
 */
public record AmendmentOptionsResponse(
        String currency,
        BigDecimal availableKg,
        BigDecimal pricePerKgDisplay,
        List<GridItemOption> gridItems,
        List<String> paymentMethods,
        OffsetDateTime expiresAt,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal availableVolumeM3,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal pricePerM3Display) {

    /** Options d'un trajet sans volume vendu (au kilo ou en capacité libre). */
    public AmendmentOptionsResponse(String currency, BigDecimal availableKg, BigDecimal pricePerKgDisplay,
                                    List<GridItemOption> gridItems, List<String> paymentMethods,
                                    OffsetDateTime expiresAt, BigDecimal minAmount, BigDecimal maxAmount) {
        this(currency, availableKg, pricePerKgDisplay, gridItems, paymentMethods, expiresAt, minAmount, maxAmount,
                null, null);
    }

    /** Article de la grille du trajet, au prix « affiché expéditeur ». */
    public record GridItemOption(UUID id, String label, BigDecimal unitPriceDisplay) {
    }
}

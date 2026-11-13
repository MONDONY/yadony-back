package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Devis avant création du bid : calcule le total exact avec promo éventuel.
 * Endpoint : POST /bids/quote (ROLE_SENDER)
 *
 * <p>Supporte les trois modes tarifaires, exactement comme la création du bid :
 * <ul>
 *   <li><b>KG</b> — {@code weightKg} seul (grille absente/vide).</li>
 *   <li><b>GRID</b> — {@code gridItems} seuls ({@code weightKg} null/0).</li>
 *   <li><b>MIXED</b> — {@code weightKg} ET {@code gridItems}.</li>
 * </ul>
 * Le promo est ainsi reflété dans l'aperçu pour TOUS les modes, pas seulement KG.
 *
 * <p>Trajet bateau vendu au m³ : les trois dimensions du colis ({@code lengthCm}, {@code widthCm},
 * {@code heightCm}, 1 à 400 cm) sont obligatoires, le volume est calculé par le serveur (jamais envoyé) et le
 * poids éventuel n'est pas tarifé. Ignorées pour tout autre trajet.
 */
public record BidQuoteRequest(
        @NotNull(message = "{validation.bid.announcement.required}")
        UUID announcementId,

        /** Poids facturé au kilo. Null/absent en mode GRID pur. Si fourni, ≥ 0.1 kg. */
        @DecimalMin(value = "0.1", message = "{validation.bid.quote.weight.min}")
        BigDecimal weightKg,

        /** Code promo optionnel (insensible à la casse). */
        String promoCode,

        /** Articles de la grille sélectionnés (modes GRID/MIXED). Null/vide en mode KG pur. */
        @Valid List<BidGridItemRequest> gridItems,

        /** Dimensions du colis en cm, trajet au m³ seulement (mêmes noms que la demande de colis). */
        @Min(1) @Max(400) Integer lengthCm,
        @Min(1) @Max(400) Integer widthCm,
        @Min(1) @Max(400) Integer heightCm
) {
    /** Sans dimensions : la forme d'avant le bateau au m³. */
    public BidQuoteRequest(UUID announcementId, BigDecimal weightKg, String promoCode,
                           List<BidGridItemRequest> gridItems) {
        this(announcementId, weightKg, promoCode, gridItems, null, null, null);
    }
}

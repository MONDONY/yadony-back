package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
        @Valid List<BidGridItemRequest> gridItems
) {}

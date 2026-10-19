package com.yadony.api.payments.supplement.dto;

import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.supplement.BidSupplementReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Demande de supplément du voyageur.
 *
 * @param netAmount     net demandé pour lui, dans la devise du colis.
 * @param photoKey      clé R2 renvoyée par {@code POST /storage/upload/tracking}, sous {@code tracking/<bidId>/}.
 * @param gridItemLabel article de la grille du trajet qui justifie le supplément (motif {@code GRID_ITEM}).
 * @param commissionSource colis payé en espèces seulement : d'où prélever la commission du supplément
 *                      au voyageur ({@code WALLET_FIRST} par défaut, ou {@code CARD} : carte commission),
 *                      même contrat que {@code POST /bids/{id}/accept-with-commission}. Ignoré sur les
 *                      autres rails.
 * @param fundingCurrency espèces, solde : portefeuille de complément choisi par le voyageur (code ISO),
 *                      nul = sa devise active. Le portefeuille de la devise du colis est toujours pris
 *                      en premier.
 */
public record CreateSupplementRequest(
        @NotNull @Positive BigDecimal netAmount,
        @NotNull BidSupplementReason reasonCode,
        @Size(max = 500) String reasonText,
        @Size(max = 120) String gridItemLabel,
        @NotBlank @Size(max = 512) String photoKey,
        CommissionSource commissionSource,
        @Size(max = 3) String fundingCurrency
) {

    /** Demande sans choix de source de commission (rails carte et mobile money). */
    public CreateSupplementRequest(BigDecimal netAmount, BidSupplementReason reasonCode, String reasonText,
                                   String gridItemLabel, String photoKey) {
        this(netAmount, reasonCode, reasonText, gridItemLabel, photoKey, null, null);
    }

    /** Source de la commission espèces : {@code WALLET_FIRST} quand l'app n'en précise aucune. */
    public CommissionSource commissionSourceOrDefault() {
        return commissionSource == null ? CommissionSource.WALLET_FIRST : commissionSource;
    }
}

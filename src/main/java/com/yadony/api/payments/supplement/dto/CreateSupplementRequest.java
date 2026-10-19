package com.yadony.api.payments.supplement.dto;

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
 */
public record CreateSupplementRequest(
        @NotNull @Positive BigDecimal netAmount,
        @NotNull BidSupplementReason reasonCode,
        @Size(max = 500) String reasonText,
        @Size(max = 120) String gridItemLabel,
        @NotBlank @Size(max = 512) String photoKey
) {}

package com.yadony.api.payments.amendment.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demande de modification d'un colis accepté par son expéditeur : kilos ajoutés, volume ajouté (trajet vendu au
 * m³) et/ou articles de la grille du trajet, payés par {@code paymentMethod} (un des rails des options, pas
 * forcément celui du colis).
 *
 * @param addedVolumeM3 m³ ajoutés, seulement sur un trajet vendu au m³ (sinon 422 {@code amendment-not-eligible}) ;
 *                      nul ou 0 si rien n'est ajouté en volume.
 */
public record CreateAmendmentRequest(
        @DecimalMin("0")
        // Maximum technique (NUMERIC(8,2), V380) : au-delà, 422 plutôt qu'une 500 SQL.
        @DecimalMax(value = "999999.99", message = "{validation.amendment.added-kg.max}") BigDecimal addedKg,
        @Valid List<AmendmentLineRequest> items,
        @NotNull PaymentMethod paymentMethod,
        @DecimalMin("0")
        // Maximum technique (NUMERIC(8,3), V384) : au-delà, 422 plutôt qu'une 500 SQL.
        @DecimalMax(value = "99999.999", message = "{validation.amendment.added-volume.max}") BigDecimal addedVolumeM3) {

    /** Demande sans volume (kilos et articles). */
    public CreateAmendmentRequest(BigDecimal addedKg, List<AmendmentLineRequest> items, PaymentMethod paymentMethod) {
        this(addedKg, items, paymentMethod, null);
    }
}

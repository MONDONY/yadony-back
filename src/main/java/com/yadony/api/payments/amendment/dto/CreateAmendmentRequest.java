package com.yadony.api.payments.amendment.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demande de modification d'un colis accepté par son expéditeur : kilos ajoutés et/ou articles de la grille
 * du trajet, payés par {@code paymentMethod} (un des rails des options, pas forcément celui du colis).
 */
public record CreateAmendmentRequest(
        @DecimalMin("0")
        // Maximum technique (NUMERIC(8,2), V380) : au-delà, 422 plutôt qu'une 500 SQL.
        @DecimalMax(value = "999999.99", message = "{validation.amendment.added-kg.max}") BigDecimal addedKg,
        @Valid List<AmendmentLineRequest> items,
        @NotNull PaymentMethod paymentMethod) {
}

package com.yadony.api.payments.amendment.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demande de modification d'un colis accepté par son expéditeur : kilos ajoutés et/ou articles de la grille
 * du trajet, payés par {@code paymentMethod} (un des rails des options, pas forcément celui du colis).
 */
public record CreateAmendmentRequest(
        @DecimalMin("0") BigDecimal addedKg,
        @Valid List<AmendmentLineRequest> items,
        @NotNull PaymentMethod paymentMethod) {
}

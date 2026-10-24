package com.yadony.api.payments.amendment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.List;

/** Demande de devis d'une modification : kilos ajoutés et/ou articles de grille. */
public record AmendmentQuoteRequest(
        @DecimalMin("0") BigDecimal addedKg,
        @Valid List<AmendmentLineRequest> items) {
}

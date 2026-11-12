package com.yadony.api.payments.amendment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.List;

/** Demande de devis d'une modification : kilos ajoutés et/ou articles de grille. */
public record AmendmentQuoteRequest(
        @DecimalMin("0")
        // Maximum technique (NUMERIC(8,2), V380) : même borne que la demande d'avenant.
        @DecimalMax(value = "999999.99", message = "{validation.amendment.added-kg.max}") BigDecimal addedKg,
        @Valid List<AmendmentLineRequest> items) {
}

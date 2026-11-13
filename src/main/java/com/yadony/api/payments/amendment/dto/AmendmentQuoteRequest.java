package com.yadony.api.payments.amendment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.List;

/** Demande de devis d'une modification : kilos ajoutés, volume ajouté (trajet au m³) et/ou articles de grille. */
public record AmendmentQuoteRequest(
        @DecimalMin("0")
        // Maximum technique (NUMERIC(8,2), V380) : même borne que la demande d'avenant.
        @DecimalMax(value = "999999.99", message = "{validation.amendment.added-kg.max}") BigDecimal addedKg,
        @Valid List<AmendmentLineRequest> items,
        @DecimalMin("0")
        // Maximum technique (NUMERIC(8,3), V384) : même borne que la demande d'avenant.
        @DecimalMax(value = "99999.999", message = "{validation.amendment.added-volume.max}") BigDecimal addedVolumeM3) {

    /** Devis sans volume (kilos et articles). */
    public AmendmentQuoteRequest(BigDecimal addedKg, List<AmendmentLineRequest> items) {
        this(addedKg, items, null);
    }
}

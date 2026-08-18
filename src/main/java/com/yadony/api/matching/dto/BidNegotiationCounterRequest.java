package com.yadony.api.matching.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Contre-offre d'un participant sur un fil de négociation de trajet. */
public record BidNegotiationCounterRequest(
        @NotNull(message = "Le montant proposé est obligatoire")
        @DecimalMin(value = "0.01", message = "Le montant minimum est 0,01")
        @DecimalMax(value = "1000000", message = "Le montant maximum est 1 000 000")
        BigDecimal proposedTotalEur,

        @Size(max = 280, message = "Le message ne peut pas dépasser 280 caractères")
        String body
) {}

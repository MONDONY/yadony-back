package com.yadony.api.matching.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Contre-offre d'un participant sur un fil de négociation de trajet. */
public record BidNegotiationCounterRequest(
        @NotNull(message = "{validation.amount.proposed.required}")
        @DecimalMin(value = "0.01", message = "{validation.amount.min-cent}")
        @DecimalMax(value = "1000000", message = "{validation.amount.max-million}")
        BigDecimal proposedTotalEur,

        @Size(max = 280, message = "{validation.negotiation.message.max}")
        String body
) {}

package com.yadony.api.requests.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

// Aucun plafond métier sur une offre (retiré le 2026-09-19, il bloquait les
// contre-offres réalistes en franc CFA). Le plancher dépend de la devise du fil
// et est appliqué par NegotiationService.assertPriceWithinBounds. Ici on ne
// garde qu'un garde-fou technique anti-abus, aligné sur le CHECK SQL (V256).
public record NegotiationCounterRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax("1000000.0") BigDecimal proposedPriceEur,
    @Size(max = 280) String body
) {}

package com.yadony.api.requests.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

// Le plafond réel dépend de la devise du fil et est appliqué par
// NegotiationService.assertPriceWithinBounds : une annotation ne peut pas le
// porter. Ici on ne garde qu'un garde-fou anti-abus, assez large pour laisser
// passer les devises sans sous-unité (327 500 XOF au plafond).
public record NegotiationCounterRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax("1000000.0") BigDecimal proposedPriceEur,
    @Size(max = 280) String body
) {}

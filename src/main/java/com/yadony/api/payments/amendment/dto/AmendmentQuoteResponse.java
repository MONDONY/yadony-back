package com.yadony.api.payments.amendment.dto;

import java.math.BigDecimal;

/** Devis vu par l'expéditeur : le brut seul, jamais le net ni la commission. */
public record AmendmentQuoteResponse(BigDecimal grossAmount, String currency) {
}

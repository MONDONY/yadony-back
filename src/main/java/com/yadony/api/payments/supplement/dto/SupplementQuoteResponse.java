package com.yadony.api.payments.supplement.dto;

import java.math.BigDecimal;

/** Devis d'un supplément pour le voyageur : son net, la commission Yadony, le total payé par l'expéditeur. */
public record SupplementQuoteResponse(BigDecimal netAmount, BigDecimal commissionAmount, BigDecimal grossAmount,
                                      String currency) {}

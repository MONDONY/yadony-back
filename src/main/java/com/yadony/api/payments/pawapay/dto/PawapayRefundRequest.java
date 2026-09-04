package com.yadony.api.payments.pawapay.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Requête d'initiation d'un remboursement — API pawaPay v2 {@code POST /v2/refunds}. */
public record PawapayRefundRequest(UUID refundId, UUID depositId, BigDecimal amount, String currency) {}

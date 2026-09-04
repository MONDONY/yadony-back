package com.yadony.api.payments.pawapay.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Requête d'initiation d'un versement (décaissement voyageur) — API pawaPay v2 {@code POST /v2/payouts}. */
public record PawapayPayoutRequest(UUID payoutId, String phoneNumber, String provider, BigDecimal amount,
                                   String currency, String customerMessage, String clientReferenceId) {}

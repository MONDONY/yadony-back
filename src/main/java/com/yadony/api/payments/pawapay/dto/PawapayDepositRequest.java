package com.yadony.api.payments.pawapay.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Requête d'initiation d'un dépôt (encaissement expéditeur) — API pawaPay v2 {@code POST /v2/deposits}. */
public record PawapayDepositRequest(UUID depositId, String phoneNumber, String provider, BigDecimal amount,
                                    String currency, String customerMessage, String clientReferenceId,
                                    String successfulUrl, String failedUrl) {}

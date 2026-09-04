package com.yadony.api.payments.pawapay.dto;

import com.yadony.api.payments.pawapay.PawapayOperationStatus;

/** Photo de l'état d'une opération pawaPay renvoyée par {@code GET /v2/{deposits|payouts|refunds}/{id}}. */
public record PawapayOperationSnapshot(PawapayOperationStatus status, String failureCode, String failureMessage,
                                       String providerTransactionId, String authorizationUrl, String raw) {}

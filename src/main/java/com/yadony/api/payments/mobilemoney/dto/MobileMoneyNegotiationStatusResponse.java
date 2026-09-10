package com.yadony.api.payments.mobilemoney.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Réponse des endpoints mobile money d'un fil (initiate / status). Numéro toujours masqué. */
public record MobileMoneyNegotiationStatusResponse(
        UUID threadId,
        /** Statut du PaymentEntity (PENDING, ESCROW, RELEASED, REFUNDED, CANCELLED) ou null s'il n'existe pas. */
        String paymentStatus,
        LocalDateTime deadlineAt,
        BigDecimal amount,
        String currency,
        MobileMoneyPaymentStatusResponse.OperationView deposit) {}

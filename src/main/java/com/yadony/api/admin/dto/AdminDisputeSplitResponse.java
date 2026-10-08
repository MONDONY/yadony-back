package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Exécution du partage d'un litige (FLUTTER-E2) : {@code status} COMPLETED, ou étape atteinte + erreur. */
public record AdminDisputeSplitResponse(
        UUID id,
        BigDecimal senderRefundAmount,
        BigDecimal travelerPayoutAmount,
        String currency,
        String mode,
        String status,
        int attempts,
        String lastError,
        String stripeRefundId,
        String stripeTransferId,
        LocalDateTime completedAt
) {}

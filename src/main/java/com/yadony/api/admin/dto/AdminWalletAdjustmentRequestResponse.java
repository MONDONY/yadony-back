package com.yadony.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.payments.wallet.WalletAdjustmentRequestEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Correction soumise à la double validation, telle que l'affiche la file d'approbation. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminWalletAdjustmentRequestResponse(
        UUID id,
        UUID userId,
        String currency,
        String direction,
        BigDecimal amount,
        BigDecimal eurEquivalent,
        String reason,
        String status,
        UUID requestedBy,
        String requestedByEmail,
        UUID decidedBy,
        String decidedByEmail,
        LocalDateTime decidedAt,
        String decisionNote,
        UUID transactionId,
        LocalDateTime createdAt
) {
    public static AdminWalletAdjustmentRequestResponse from(WalletAdjustmentRequestEntity r, String requestedByEmail,
                                                            String decidedByEmail) {
        return new AdminWalletAdjustmentRequestResponse(r.getId(), r.getUserId(), r.getCurrency(),
                r.getDirection().name(), r.getAmount(), r.getEurEquivalent(), r.getReason(), r.getStatus().name(),
                r.getRequestedBy(), requestedByEmail, r.getDecidedBy(), decidedByEmail, r.getDecidedAt(),
                r.getDecisionNote(), r.getTransactionId(), r.getCreatedAt());
    }
}

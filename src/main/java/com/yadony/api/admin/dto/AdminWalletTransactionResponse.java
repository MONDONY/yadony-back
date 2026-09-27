package com.yadony.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.yadony.api.payments.wallet.WalletTransactionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Un mouvement du journal wallet. {@code amount} est signé (négatif au débit).
 * {@code adminReason}, {@code adminActorId} et {@code adminActorEmail} ne sont renseignés que
 * sur une correction admin ({@code ADMIN_CREDIT} / {@code ADMIN_DEBIT}).
 */
// Clés toujours présentes, à null si besoin : contrat de l'écran admin, quelle que soit
// l'inclusion Jackson globale (NON_NULL).
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminWalletTransactionResponse(
        UUID id,
        String currency,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID bidId,
        String paymentRef,
        Instant createdAt,
        String adminReason,
        UUID adminActorId,
        String adminActorEmail) {

    public static AdminWalletTransactionResponse from(WalletTransactionEntity tx, String adminActorEmail) {
        return new AdminWalletTransactionResponse(tx.getId(), tx.getCurrency(), tx.getType().name(), tx.getAmount(),
                tx.getBalanceAfter(), tx.getBidId(), tx.getPaymentRef(), tx.getCreatedAt(), tx.getAdminReason(),
                tx.getAdminActorId(), adminActorEmail);
    }
}

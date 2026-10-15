package com.yadony.api.admin.dto;

import com.yadony.api.payments.wallet.WalletRefundRequestEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne de la file des remboursements wallet de l'admin.
 *
 * <p>{@code channel} : AUTOMATIC_STRIPE / AUTOMATIC_PAWAPAY (rien à faire, suivi seulement) ou
 * MANUAL_ADMIN (à rembourser hors-app puis résoudre). {@code origin} : ACCOUNT_DELETION quand la
 * demande date de la suppression du compte (ou après), sinon WALLET (demande depuis l'écran
 * wallet). {@code userName} et {@code origin} sont nuls sur la réponse de résolution, qui ne
 * relit pas l'utilisateur.
 */
public record AdminWalletRefundRequestResponse(
        UUID id,
        UUID userId,
        String currency,
        BigDecimal amount,
        String status,
        LocalDateTime requestedAt,
        String channel,
        UUID parentRequestId,
        LocalDateTime resolvedAt,
        String userName,
        String origin
) {
    public static final String ORIGIN_ACCOUNT_DELETION = "ACCOUNT_DELETION";
    public static final String ORIGIN_WALLET = "WALLET";

    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e) {
        return from(e, null, null);
    }

    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e, String userName, String origin) {
        return new AdminWalletRefundRequestResponse(
                e.getId(), e.getUserId(), e.getCurrency(), e.getAmount(),
                e.getStatus().name(), e.getRequestedAt(),
                e.getChannel() != null ? e.getChannel().name() : null,
                e.getParentRequestId(), e.getResolvedAt(), userName, origin);
    }
}

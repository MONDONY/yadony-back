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
 *
 * <p>Champs additifs (V330) : {@code paidAmount} et {@code resolutionNote}, le montant
 * réellement versé hors app et la note de l'admin à la résolution manuelle (nuls sinon) ;
 * {@code feeAmount}, somme des frais des items hors FAILED (nul sans item, et sur la réponse de
 * résolution, qui ne relit pas les items).
 *
 * <p>Champs additifs (relance mobile money) : {@code sourceRail}, rail des recharges que la
 * demande rembourse ({@code PAWAPAY}, {@code STRIPE}, {@code MIXED}, nul sans item) : l'admin ne
 * propose « Relancer le remboursement » que sur un ticket manuel {@code PAWAPAY} ;
 * {@code failureReason}, dernier motif d'échec connu (items en échec de la demande, sinon de sa
 * demande parente), nul sinon.
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
        String origin,
        BigDecimal paidAmount,
        String resolutionNote,
        BigDecimal feeAmount,
        String sourceRail,
        String failureReason
) {
    public static final String ORIGIN_ACCOUNT_DELETION = "ACCOUNT_DELETION";
    public static final String ORIGIN_WALLET = "WALLET";

    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e) {
        return from(e, null, null, null);
    }

    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e, String userName, String origin) {
        return from(e, userName, origin, null);
    }

    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e, String userName, String origin,
                                                        BigDecimal feeAmount) {
        return from(e, userName, origin, feeAmount, null, null);
    }

    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e, String userName, String origin,
                                                        BigDecimal feeAmount, String sourceRail,
                                                        String failureReason) {
        return new AdminWalletRefundRequestResponse(
                e.getId(), e.getUserId(), e.getCurrency(), e.getAmount(),
                e.getStatus().name(), e.getRequestedAt(),
                e.getChannel() != null ? e.getChannel().name() : null,
                e.getParentRequestId(), e.getResolvedAt(), userName, origin,
                e.getPaidAmount(), e.getResolutionNote(), feeAmount, sourceRail, failureReason);
    }
}

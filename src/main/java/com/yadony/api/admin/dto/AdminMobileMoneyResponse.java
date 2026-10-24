package com.yadony.api.admin.dto;

import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une opération pawaPay en lecture seule. Le numéro ne quitte le serveur que masqué
 * ({@code msisdn_masked}, calculé à l'écriture) : la colonne chiffrée n'est jamais exposée.
 *
 * <p>Champs ajoutés en fin (absents du JSON quand ils sont nuls) : finalité de l'opération, personne concernée
 * et son rôle ({@code SENDER}, {@code TRAVELER} ou {@code USER}), colis, n° de suivi et trajet
 * (« Paris → Dakar »), demande de remboursement du solde servie, et {@code retryable} : la relance du versement
 * ou du remboursement serait acceptée par l'admin.
 */
public record AdminMobileMoneyResponse(
        UUID id,
        UUID paymentId,
        String kind,
        String provider,
        String countryCode,
        String phoneNumber,
        long amountCents,
        String currency,
        String status,
        String failureCode,
        LocalDateTime createdAt,
        String purpose,
        UUID personId,
        String personName,
        String role,
        UUID bidId,
        String trackingNumber,
        String corridor,
        UUID walletRefundRequestId,
        boolean retryable) {

    /** Personne, colis et relance résolus par la liste ({@code AdminFinanceListings}). */
    public record Context(UUID personId, String personName, String role, UUID bidId, String trackingNumber,
                          String corridor, UUID walletRefundRequestId, boolean retryable) {
    }

    public static AdminMobileMoneyResponse from(PawapayOperationEntity op, Context c) {
        return new AdminMobileMoneyResponse(op.getId(), op.getPaymentId(), op.getKind().name(), op.getProvider(),
                op.getCountry(), op.getMsisdnMasked(), AdminWalletResponse.toCents(op.getAmount()), op.getCurrency(),
                op.getStatus().name(), op.getFailureCode(), op.getCreatedAt(),
                op.getPurpose() != null ? op.getPurpose().name() : null,
                c.personId(), c.personName(), c.role(), c.bidId(), c.trackingNumber(), c.corridor(),
                c.walletRefundRequestId(), c.retryable());
    }
}

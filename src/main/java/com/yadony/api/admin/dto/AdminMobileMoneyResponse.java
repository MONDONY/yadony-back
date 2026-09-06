package com.yadony.api.admin.dto;

import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une opération pawaPay en lecture seule. Le numéro ne quitte le serveur que masqué
 * ({@code msisdn_masked}, calculé à l'écriture) : la colonne chiffrée n'est jamais exposée.
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
        LocalDateTime createdAt) {

    public static AdminMobileMoneyResponse from(PawapayOperationEntity op) {
        return new AdminMobileMoneyResponse(op.getId(), op.getPaymentId(), op.getKind().name(), op.getProvider(),
                op.getCountry(), op.getMsisdnMasked(), AdminWalletResponse.toCents(op.getAmount()), op.getCurrency(),
                op.getStatus().name(), op.getFailureCode(), op.getCreatedAt());
    }
}

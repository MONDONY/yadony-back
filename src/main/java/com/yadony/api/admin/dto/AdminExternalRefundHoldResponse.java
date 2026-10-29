package com.yadony.api.admin.dto;

import com.yadony.api.payments.wallet.WalletExternalRefundHoldEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Gel posé sur une devise après un remboursement Stripe fait hors de l'app (fiche utilisateur). */
public record AdminExternalRefundHoldResponse(
        UUID id,
        String currency,
        BigDecimal amount,
        String paymentIntentId,
        String stripeRefundId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime releasedAt,
        String releasedByEmail,
        String releaseNote
) {
    public static AdminExternalRefundHoldResponse from(WalletExternalRefundHoldEntity h, String releasedByEmail) {
        return new AdminExternalRefundHoldResponse(h.getId(), h.getCurrency(), h.getAmount(), h.getPaymentIntentId(),
                h.getStripeRefundId(), h.getStatus().name(), h.getCreatedAt(), h.getReleasedAt(), releasedByEmail,
                h.getReleaseNote());
    }
}

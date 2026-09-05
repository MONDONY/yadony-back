package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Détail d'un paiement, tous rails confondus depuis la tâche 18.
 *
 * <p>{@code method} reste "STRIPE"/"PAWAPAY" pour ne pas casser un appelant existant qui lisait
 * déjà ce champ ; {@code rail} porte la même valeur sous un nom explicite pour les nouveaux
 * usages admin. Les trois identifiants {@code pawapayXxxId} sont un confort de lecture — comme
 * sur {@link com.yadony.api.payments.PaymentEntity}, l'autorité reste
 * {@code pawapay_operations.payment_id} (spec §7.1) — et restent {@code null} pour un paiement
 * STRIPE.
 */
public record AdminPaymentDetailResponse(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        LocalDateTime createdAt,
        long refundedCents,
        String stripePaymentIntentId,
        LocalDateTime escrowReleasedAt,
        boolean disputed,
        String rail,
        UUID pawapayDepositId,
        UUID pawapayPayoutId,
        UUID pawapayRefundId
) {
    public static AdminPaymentDetailResponse from(PaymentEntity p) {
        return new AdminPaymentDetailResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                p.getRail().name(),
                p.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue(),
                p.getCommissionAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue(),
                p.getCreatedAt(),
                p.getRefundedAmount() != null
                        ? p.getRefundedAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue()
                        : 0L,
                p.getStripePaymentIntentId(),
                p.getEscrowReleasedAt(),
                p.isDisputed(),
                p.getRail().name(),
                p.getPawapayDepositId(),
                p.getPawapayPayoutId(),
                p.getPawapayRefundId()
        );
    }
}

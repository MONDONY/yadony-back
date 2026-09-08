package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Détail d'un paiement, tous rails confondus.
 *
 * <p>{@code method} reste "STRIPE"/"PAWAPAY" pour ne pas casser un appelant existant qui lisait
 * déjà ce champ ; {@code rail} porte la même valeur sous un nom explicite pour les nouveaux
 * usages admin. Les trois identifiants {@code pawapayXxxId} sont la dernière opération de chaque
 * type dans {@code pawapay_operations} (le seul lien qui fait autorité, spec §7.1), fournis par
 * le contrôleur ; {@code null} pour un paiement STRIPE.
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
    /** Paiement sans opération pawaPay (rail STRIPE). */
    public static AdminPaymentDetailResponse from(PaymentEntity p) {
        return from(p, null, null, null);
    }

    public static AdminPaymentDetailResponse from(PaymentEntity p, UUID pawapayDepositId, UUID pawapayPayoutId,
                                                  UUID pawapayRefundId) {
        return new AdminPaymentDetailResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                p.getRail().name(),
                AdminWalletResponse.toCents(p.getAmount()),
                AdminWalletResponse.toCents(p.getCommissionAmount()),
                p.getCreatedAt(),
                AdminWalletResponse.toCents(p.getRefundedAmount()),
                p.getStripePaymentIntentId(),
                p.getEscrowReleasedAt(),
                p.isDisputed(),
                p.getRail().name(),
                pawapayDepositId,
                pawapayPayoutId,
                pawapayRefundId
        );
    }
}

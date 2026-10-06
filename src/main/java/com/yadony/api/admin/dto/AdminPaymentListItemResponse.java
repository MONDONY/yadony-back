package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.hold.PayoutHoldStatus;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

public record AdminPaymentListItemResponse(
        UUID id,
        UUID bidId,
        String status,
        String method,
        long amountCents,
        long commissionCents,
        /** Devise du paiement en majuscules : sans elle, le back-office affichait tout en euros. */
        String currency,
        LocalDateTime createdAt,
        /**
         * Voyageur beneficiaire (annonce du colis, classique ou materialise depuis un fil de
         * negociation) ; {@code null} si le colis n'est pas encore materialise.
         */
        UUID travelerId,
        /** Versement retenu a la livraison, beneficiaire gele (V270) ; {@code null} sinon. */
        LocalDateTime payoutHeldAt,
        /** Le voyageur beneficiaire est actuellement gele (banni ou KYC retire). */
        boolean beneficiaryHeld,
        /** Motif principal du gel : {@code BANNED} ou {@code KYC_REVOKED} ; {@code null} si non gele. */
        String beneficiaryHoldReason,
        /** Contexte (colis ou négociation, parties, trajet, liens Stripe) ; {@code null} hors back-office. */
        AdminPaymentInsight insight
) {
    public static AdminPaymentListItemResponse from(PaymentEntity p) {
        return from(p, null, PayoutHoldStatus.NONE);
    }

    public static AdminPaymentListItemResponse from(PaymentEntity p, UUID travelerId, PayoutHoldStatus hold) {
        PayoutHoldStatus h = hold != null ? hold : PayoutHoldStatus.NONE;
        return new AdminPaymentListItemResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                p.getRail().name(),
                AdminWalletResponse.toCents(p.getAmount()),
                AdminWalletResponse.toCents(p.getCommissionAmount()),
                p.getCurrency().toUpperCase(Locale.ROOT),
                p.getCreatedAt(),
                travelerId,
                p.getPayoutHeldAt(),
                h.held(),
                h.primaryReason() != null ? h.primaryReason().name() : null,
                null
        );
    }

    public AdminPaymentListItemResponse withInsight(AdminPaymentInsight value) {
        return new AdminPaymentListItemResponse(id, bidId, status, method, amountCents, commissionCents, currency,
                createdAt, travelerId, payoutHeldAt, beneficiaryHeld, beneficiaryHoldReason, value);
    }
}

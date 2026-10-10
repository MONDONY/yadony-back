package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.hold.PayoutHoldStatus;

import java.time.LocalDateTime;
import java.util.Locale;
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
        /** Devise du paiement en majuscules : un paiement mobile money est en XOF ou XAF, jamais en euros. */
        String currency,
        LocalDateTime createdAt,
        long refundedCents,
        String stripePaymentIntentId,
        LocalDateTime escrowReleasedAt,
        boolean disputed,
        String rail,
        UUID pawapayDepositId,
        UUID pawapayPayoutId,
        UUID pawapayRefundId,
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
        AdminPaymentInsight insight,
        /**
         * Capture carte enregistrée ({@code payments.captured_at}) ; {@code null} si le séquestre n'a
         * jamais été marqué capturé. Lu en base, sans appel Stripe : l'état Stripe réel s'obtient par
         * {@code POST /admin/payments/{id}/resync-stripe}.
         */
        java.time.Instant capturedAt,
        /** Fil de négociation du paiement ({@code bid_id} nul tant que le colis n'est pas matérialisé). */
        UUID negotiationThreadId
) {
    /** Paiement sans opération pawaPay (rail STRIPE). */
    public static AdminPaymentDetailResponse from(PaymentEntity p) {
        return from(p, null, null, null);
    }

    public static AdminPaymentDetailResponse from(PaymentEntity p, UUID pawapayDepositId, UUID pawapayPayoutId,
                                                  UUID pawapayRefundId) {
        return from(p, pawapayDepositId, pawapayPayoutId, pawapayRefundId, null, PayoutHoldStatus.NONE);
    }

    public static AdminPaymentDetailResponse from(PaymentEntity p, UUID pawapayDepositId, UUID pawapayPayoutId,
                                                  UUID pawapayRefundId, UUID travelerId, PayoutHoldStatus hold) {
        PayoutHoldStatus h = hold != null ? hold : PayoutHoldStatus.NONE;
        return new AdminPaymentDetailResponse(
                p.getId(),
                p.getBidId(),
                p.getStatus().name(),
                p.getRail().name(),
                AdminWalletResponse.toCents(p.getAmount()),
                AdminWalletResponse.toCents(p.getCommissionAmount()),
                p.getCurrency().toUpperCase(Locale.ROOT),
                p.getCreatedAt(),
                AdminWalletResponse.toCents(p.getRefundedAmount()),
                p.getStripePaymentIntentId(),
                p.getEscrowReleasedAt(),
                p.isDisputed(),
                p.getRail().name(),
                pawapayDepositId,
                pawapayPayoutId,
                pawapayRefundId,
                travelerId,
                p.getPayoutHeldAt(),
                h.held(),
                h.primaryReason() != null ? h.primaryReason().name() : null,
                null,
                p.getCapturedAt(),
                p.getNegotiationThreadId()
        );
    }

    public AdminPaymentDetailResponse withInsight(AdminPaymentInsight value) {
        return new AdminPaymentDetailResponse(id, bidId, status, method, amountCents, commissionCents, currency,
                createdAt, refundedCents, stripePaymentIntentId, escrowReleasedAt, disputed, rail, pawapayDepositId,
                pawapayPayoutId, pawapayRefundId, travelerId, payoutHeldAt, beneficiaryHeld, beneficiaryHoldReason,
                value, capturedAt, negotiationThreadId);
    }
}

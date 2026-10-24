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
        AdminPaymentInsight insight,
        /** Alerte la plus urgente ({@code HELD}, {@code CHARGEBACK_OPEN}, {@code MM_FAILED}, {@code ESCROW_J48}) ; absente sinon. */
        String alert,
        /** N° de suivi du colis (direct ou lié au fil) ; absent si aucun colis. */
        String trackingNumber,
        /** Annonce du trajet ; absente tant que le fil n'a pas de colis. */
        UUID announcementId
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
                null, null, null, null
        );
    }

    public AdminPaymentListItemResponse withInsight(AdminPaymentInsight value) {
        return new AdminPaymentListItemResponse(id, bidId, status, method, amountCents, commissionCents, currency,
                createdAt, travelerId, payoutHeldAt, beneficiaryHeld, beneficiaryHoldReason, value,
                alert, trackingNumber, announcementId);
    }

    /** Ajoute l'étiquette d'alerte, le n° de suivi et l'annonce (les champs ajoutés après {@code insight}). */
    public AdminPaymentListItemResponse withListFields(String alertCode, String tracking, UUID announcement) {
        return new AdminPaymentListItemResponse(id, bidId, status, method, amountCents, commissionCents, currency,
                createdAt, travelerId, payoutHeldAt, beneficiaryHeld, beneficiaryHoldReason, insight,
                alertCode, tracking, announcement);
    }
}

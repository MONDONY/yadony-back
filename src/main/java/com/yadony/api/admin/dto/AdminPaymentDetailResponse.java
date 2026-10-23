package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.hold.PayoutHoldStatus;

import java.time.LocalDateTime;
import java.util.List;
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
        UUID negotiationThreadId,
        /** Alerte la plus urgente du paiement ({@code AdminPaymentAlert}, rang exclusif) ; absente si aucune. */
        String alert,
        /** N° de suivi du colis direct ou lié au fil ; absent si inconnu. */
        String trackingNumber,
        /**
         * Identifiants des fiches liées, chacun présent seulement si l'admin détient la permission de sa
         * destination ; absent tout entier quand aucun ne l'est (jamais d'objet vide).
         */
        Links links,
        /** Alertes non résolues citant le paiement (ALERT_VIEW), plus récentes d'abord ; absentes sans le droit. */
        List<OpenAlert> openAlerts,
        /** Litige bancaire le plus récent du paiement ; absent s'il n'y en a pas. */
        Chargeback chargeback
) {
    /**
     * Fiches liées : {@code announcementId}/{@code packageRequestId} (BID_VIEW), {@code disputeId},
     * {@code disputeStatus}, {@code cancellationId} (DISPUTE_VIEW), {@code conversationId} (MODERATION_VIEW,
     * identifiant Firestore), {@code chargebackId} (PAYMENT_VIEW).
     */
    public record Links(UUID announcementId, UUID disputeId, String disputeStatus, UUID cancellationId,
                        String conversationId, UUID chargebackId, UUID packageRequestId) {
    }

    /**
     * Aucun identifiant visible : le bloc est omis plutôt que rendu vide. Méthode statique : un getter
     * d'instance du record serait sérialisé par Jackson.
     */
    private static boolean noLink(Links l) {
        return l == null || (l.announcementId() == null && l.disputeId() == null && l.disputeStatus() == null
                && l.cancellationId() == null && l.conversationId() == null && l.chargebackId() == null
                && l.packageRequestId() == null);
    }

    public record OpenAlert(UUID id, String type, String severity) {
    }

    public record Chargeback(UUID id, String status, String reason, String outcome, LocalDateTime openedAt) {
    }

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
                p.getNegotiationThreadId(),
                null, null, null, null, null
        );
    }

    public AdminPaymentDetailResponse withInsight(AdminPaymentInsight value) {
        return new AdminPaymentDetailResponse(id, bidId, status, method, amountCents, commissionCents, currency,
                createdAt, refundedCents, stripePaymentIntentId, escrowReleasedAt, disputed, rail, pawapayDepositId,
                pawapayPayoutId, pawapayRefundId, travelerId, payoutHeldAt, beneficiaryHeld, beneficiaryHoldReason,
                value, capturedAt, negotiationThreadId, alert, trackingNumber, links, openAlerts, chargeback);
    }

    /** Ajouts de la fiche ({@code GET /admin/payments/{id}}), déjà filtrés par permission. */
    public AdminPaymentDetailResponse withSheet(String alertValue, String trackingValue, Links linksValue,
                                                List<OpenAlert> openAlertsValue, Chargeback chargebackValue) {
        return new AdminPaymentDetailResponse(id, bidId, status, method, amountCents, commissionCents, currency,
                createdAt, refundedCents, stripePaymentIntentId, escrowReleasedAt, disputed, rail, pawapayDepositId,
                pawapayPayoutId, pawapayRefundId, travelerId, payoutHeldAt, beneficiaryHeld, beneficiaryHoldReason,
                insight, capturedAt, negotiationThreadId, alertValue, trackingValue,
                noLink(linksValue) ? null : linksValue, openAlertsValue, chargebackValue);
    }
}

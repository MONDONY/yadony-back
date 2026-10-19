package com.yadony.api.payments.mobilemoney.dto;

import com.yadony.api.payments.pawapay.ValidationHint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Réponse commune aux trois endpoints du rail mobile money (accept / initiate / status).
 * {@code deposit} est {@code null} tant qu'aucune opération de dépôt n'existe encore.
 */
public record MobileMoneyPaymentStatusResponse(
        UUID bidId,
        String bidStatus,
        /** Statut du PaymentEntity (PENDING, ESCROW, RELEASED, REFUNDED, CANCELLED) ou null s'il n'existe pas encore. */
        String paymentStatus,
        LocalDateTime deadlineAt,
        BigDecimal amount,
        String currency,
        /** Dernière opération de deposit, ou null. */
        OperationView deposit) {

    /**
     * Numéro toujours sous forme masquée ({@link com.yadony.api.common.Msisdn#mask}) — jamais en clair.
     * {@code failureCode} est le code pawaPay brut (anciens clients), {@code failureReasonCode} le code
     * Yadony stable, {@code failureReason} le texte traduit (repris dans {@code failureMessage}) ; ces
     * trois champs sont {@code null} tant que le dépôt n'est pas en échec. {@code validationHint} dit
     * comment l'opérateur demande de valider le paiement.
     */
    public record OperationView(UUID id, String status, String provider, String providerLabel, String msisdnMasked,
                                String authorizationUrl, String failureCode, String failureMessage,
                                String failureReasonCode, String failureReason, ValidationHint validationHint) {}
}

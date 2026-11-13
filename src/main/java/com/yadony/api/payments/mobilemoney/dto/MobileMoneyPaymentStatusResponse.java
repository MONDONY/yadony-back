package com.yadony.api.payments.mobilemoney.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.payments.pawapay.ValidationHint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Réponse commune aux trois endpoints du rail mobile money (accept / initiate / status).
 * {@code deposit} est {@code null} tant qu'aucune opération de dépôt n'existe encore.
 * {@code payoutTrigger} : {@code DELIVERY} ou {@code DEPOSIT} (transporteur payé au dépôt du colis), tel que figé à
 * l'acceptation du colis ; {@code null} tant qu'il n'est pas figé (l'app lit alors « à la livraison »). Toujours
 * sérialisé.
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
        OperationView deposit,
        @JsonInclude(JsonInclude.Include.ALWAYS) String payoutTrigger) {

    /** Réponse sans règle de versement connue (supplément, anciens appelants). */
    public MobileMoneyPaymentStatusResponse(UUID bidId, String bidStatus, String paymentStatus, LocalDateTime deadlineAt,
                                            BigDecimal amount, String currency, OperationView deposit) {
        this(bidId, bidStatus, paymentStatus, deadlineAt, amount, currency, deposit, null);
    }

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

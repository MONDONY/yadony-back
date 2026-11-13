package com.yadony.api.payments.mobilemoney.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Réponse des endpoints mobile money d'un fil (initiate / status). Numéro toujours masqué. {@code payoutTrigger} :
 * règle de versement du colis matérialisé par le fil ({@code DELIVERY} ou {@code DEPOSIT}), figée à sa matérialisation ;
 * {@code null} tant que le colis n'existe pas (l'app lit alors « à la livraison »). Toujours sérialisé.
 */
public record MobileMoneyNegotiationStatusResponse(
        UUID threadId,
        /** Statut du PaymentEntity (PENDING, ESCROW, RELEASED, REFUNDED, CANCELLED) ou null s'il n'existe pas. */
        String paymentStatus,
        LocalDateTime deadlineAt,
        BigDecimal amount,
        String currency,
        MobileMoneyPaymentStatusResponse.OperationView deposit,
        @JsonInclude(JsonInclude.Include.ALWAYS) String payoutTrigger) {

    /** Réponse sans règle de versement (colis pas encore matérialisé). */
    public MobileMoneyNegotiationStatusResponse(UUID threadId, String paymentStatus, LocalDateTime deadlineAt,
                                                BigDecimal amount, String currency,
                                                MobileMoneyPaymentStatusResponse.OperationView deposit) {
        this(threadId, paymentStatus, deadlineAt, amount, currency, deposit, null);
    }

    /** Même réponse, avec la règle de versement du colis matérialisé. */
    public MobileMoneyNegotiationStatusResponse withPayoutTrigger(String trigger) {
        return new MobileMoneyNegotiationStatusResponse(threadId, paymentStatus, deadlineAt, amount, currency, deposit,
                trigger);
    }
}

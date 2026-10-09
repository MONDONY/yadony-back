package com.yadony.api.payments.overview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ligne brute du read-model {@link MoneyOverviewReadModel} : un colis de l'utilisateur et, s'il
 * existe, son paiement. Les champs {@code payment*} sont {@code null} pour un colis en espèces.
 *
 * <p>{@code escrow_released_at} et {@code updated_at} sont des {@code TIMESTAMPTZ} (lus en
 * {@code OffsetDateTime}) ; {@code payout_held_at} est un {@code TIMESTAMP} sans fuseau, dont seule
 * la présence compte ici.
 */
public record MoneyRow(
        MoneyRole role,
        UUID bidId,
        String bidStatus,
        String bidPaymentMethod,
        String bidCurrency,
        String trackingNumber,
        BigDecimal weightKg,
        String commissionStatus,
        UUID announcementId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        UUID counterpartyId,
        UUID paymentId,
        String paymentStatus,
        String rail,
        BigDecimal amount,
        BigDecimal commissionAmount,
        BigDecimal refundedAmount,
        String paymentCurrency,
        boolean chargeback,
        LocalDateTime payoutHeldAt,
        OffsetDateTime escrowReleasedAt,
        OffsetDateTime paymentUpdatedAt,
        long openDisputes,
        long allDisputes,
        OffsetDateTime holdUntil,
        long openPayouts,
        long openRefunds) {

    boolean hasPayment() {
        return paymentId != null;
    }
}

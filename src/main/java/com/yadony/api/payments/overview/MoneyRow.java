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
 *
 * <p>{@code arrivalDate} : date d'arrivée effective du trajet, {@code announcements.arrival_date}
 * quand le voyage arrive après le jour du départ, sinon le jour du départ.
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
        LocalDate arrivalDate,
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
        long openRefunds,
        BankPayoutRow bankPayout) {

    /** Lignes construites sans suivi de virement bancaire (espèces, mobile money, tests). */
    public MoneyRow(MoneyRole role, UUID bidId, String bidStatus, String bidPaymentMethod, String bidCurrency,
                    String trackingNumber, BigDecimal weightKg, String commissionStatus, UUID announcementId,
                    String departureCity, String arrivalCity, LocalDate departureDate, LocalDate arrivalDate,
                    UUID counterpartyId, UUID paymentId, String paymentStatus, String rail, BigDecimal amount,
                    BigDecimal commissionAmount, BigDecimal refundedAmount, String paymentCurrency, boolean chargeback,
                    LocalDateTime payoutHeldAt, OffsetDateTime escrowReleasedAt, OffsetDateTime paymentUpdatedAt,
                    long openDisputes, long allDisputes, OffsetDateTime holdUntil, long openPayouts, long openRefunds) {
        this(role, bidId, bidStatus, bidPaymentMethod, bidCurrency, trackingNumber, weightKg, commissionStatus,
                announcementId, departureCity, arrivalCity, departureDate, arrivalDate, counterpartyId, paymentId,
                paymentStatus, rail, amount, commissionAmount, refundedAmount, paymentCurrency, chargeback,
                payoutHeldAt, escrowReleasedAt, paymentUpdatedAt, openDisputes, allDisputes, holdUntil, openPayouts,
                openRefunds, null);
    }

    /**
     * Suivi du virement bancaire (table {@code bank_payout_tracking}, V331) d'un paiement carte
     * transféré au voyageur ; {@code null} sinon.
     */
    public record BankPayoutRow(LocalDate availableOn, String payoutStatus, LocalDate arrivalDate,
                                java.time.Instant paidAt, String failureCode) {}

    boolean hasPayment() {
        return paymentId != null;
    }
}

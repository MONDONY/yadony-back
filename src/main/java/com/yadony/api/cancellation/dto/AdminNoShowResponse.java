package com.yadony.api.cancellation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Une déclaration de no-show telle que l'écran admin Incidents > No-shows la lit.
 *
 * <p>Rétrocompatible avec l'ancien {@code AdminCancellationResponse} : {@code id},
 * {@code bidId}, {@code cancelledBy}, {@code reason}, {@code noShowStatus},
 * {@code contestationDeadline} et {@code createdAt} gardent leur nom et leur sens
 * ({@code noShowStatus} = {@code status}).
 *
 * @param scope            HANDOVER (remise au départ) ou DELIVERY (livraison à l'arrivée)
 * @param remainingMinutes minutes avant l'échéance de contestation, seulement en PENDING_CONFIRMATION (0 si dépassée)
 * @param handoverAt       heure de remise prévue (bid, sinon annonce)
 * @param amount           montant du paiement du bid, sinon le prix négocié ; nul pour une remise en espèces sans paiement
 * @param paymentStatus    statut du paiement ; pour une remise en espèces sans paiement, statut de la commission
 * @param dispute          litige lié (contestation ou « non contesté »), l'ouvert en priorité
 * @param canConfirm       l'appelant a DISPUTE_RESOLVE et la déclaration n'est pas encore tranchée
 * @param adminDecision    CONFIRMED / REJECTED si un administrateur a tranché, sinon nul
 * @param decisionReason   motif interne saisi par l'administrateur (jamais envoyé aux parties)
 */
public record AdminNoShowResponse(
        UUID id,
        UUID bidId,
        String scope,
        String reason,
        String status,
        String noShowStatus,
        UUID cancelledBy,
        OffsetDateTime contestationDeadline,
        Long remainingMinutes,
        LocalDateTime createdAt,
        Party declarant,
        Party accused,
        Trip trip,
        LocalDateTime handoverAt,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String paymentStatus,
        String commissionStatus,
        String bidStatus,
        DisputeRef dispute,
        boolean canConfirm,
        boolean canReject,
        String adminDecision,
        OffsetDateTime decidedAt,
        String decisionReason
) {

    /** @param role SENDER, TRAVELER ou RECIPIENT (destinataire : souvent sans compte, userId nul) */
    public record Party(UUID userId, String name, String role) {
    }

    public record Trip(String departureCity, String arrivalCity, LocalDate departureDate) {
    }

    public record DisputeRef(UUID id, String status) {
    }
}

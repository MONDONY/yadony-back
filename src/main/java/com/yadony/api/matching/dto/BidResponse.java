package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record BidResponse(
        UUID id,
        UUID announcementId,
        UUID senderId,
        String senderName,
        /**
         * Le numéro de l'expéditeur est joignable : le client peut afficher le bouton
         * d'appel. Le numéro lui-même s'obtient via {@code GET /bids/{id}/contact}, au
         * moment du tap — il ne voyage pas dans les réponses de liste.
         */
        boolean senderPhoneAvailable,
        Integer senderTotalShipments,
        boolean senderKycVerified,
        boolean senderIsProAccount,
        boolean senderKiloPro,
        BigDecimal weightKg,
        String description,
        String contentCategory,
        String recipientName,
        String recipientPhone,
        String status,
        String rejectionReason,
        String handoverLocation,
        LocalDateTime handoverWindowStart,
        LocalDateTime handoverWindowEnd,
        boolean voyageurConfirmed,
        LocalDateTime disclaimerSignedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        BigDecimal pricePerKg,
        /** Tarif/kg BRUT affiché à l'expéditeur (net + commission). L'expéditeur
         * ne reçoit jamais le tarif net {@code pricePerKg}. */
        BigDecimal pricePerKgSenderEur,
        com.yadony.api.matching.TransportMode transportMode,
        String trackingNumber,
        String trackingToken,
        String confirmationCode,
        UUID travelerId,
        String travelerName,
        /** Idem {@code senderPhoneAvailable}, côté voyageur. */
        boolean travelerPhoneAvailable,
        boolean travelerKycVerified,
        boolean travelerIsProAccount,
        boolean travelerKiloPro,
        Integer travelerTotalTrips,
        java.math.BigDecimal travelerAverageRating,
        boolean senderHasRated,
        boolean travelerHasRated,
        Integer confirmationCodeRefreshCount,
        LocalDateTime confirmationCodeRefreshWindowStart,
        String cancellationNoShowStatus,
        java.time.OffsetDateTime contestationDeadline,
        String deliveryNoShowStatus,
        java.time.OffsetDateTime deliveryNoShowContestationDeadline,
        Boolean deliveryNoShowReportedByTraveler,
        String paymentMethod,
        com.yadony.api.matching.BidPricingMode pricingMode,
        BigDecimal totalNetAmountEur,
        BigDecimal totalSenderAmountEur,
        java.time.OffsetDateTime departureAt,
        String returnCode,
        java.time.LocalDateTime returnDeadline,
        java.time.LocalDateTime returnedAt,
        String senderAvatarUrl,
        String travelerAvatarUrl,
        java.util.List<com.yadony.api.matching.dto.BidPhotoResponse> photos,
        /** ID de la {@code CancellationEntity} ouvrant droit au rematch (trajet annulé OU
         * transport annulé/refusé par le voyageur) — distinct des cancellations no-show /
         * après-remise, qui n'ouvrent pas droit au rematch. Null si le bid n'a pas été
         * affecté par une cancellation rematch. */
        UUID tripCancellationId,
        /** {@code rematchStatus} de cette cancellation ("NONE" / "SUGGESTED") — permet au
         * front d'afficher le CTA « Voir les trajets alternatifs ». Null si pas de
         * cancellation ouvrant droit au rematch pour ce bid. */
        String tripCancellationRematchStatus,
        String currency
) {}

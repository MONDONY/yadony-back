package com.yadony.api.admin.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fiche admin d'une demande d'envoi : champs de la liste, contenu complet, négociations,
 * signalements et verdicts de modération. {@code photos} sont des URLs présignées (courte
 * durée), jamais des URLs publiques. {@code removeBlockedReason} vaut {@code null} quand
 * {@code canRemove}, sinon le code du 409 que renverrait le retrait.
 */
public record AdminPackageRequestDetailResponse(
        UUID id,
        UUID senderId,
        String senderName,
        String departureCity,
        String arrivalCity,
        LocalDate desiredDate,
        int dateToleranceDays,
        BigDecimal weightKg,
        ParcelSize parcelSize,
        TransportMode transportMode,
        PackageRequestStatus status,
        String currency,
        BigDecimal targetPrice,
        LocalDateTime createdAt,
        long reportCount,
        long openNegotiationCount,
        String description,
        String contentCategory,
        String pickupNeighborhood,
        String deliveryNeighborhood,
        String pickupAddressLabel,
        String deliveryAddressLabel,
        String recipientCity,
        Set<PaymentMethod> acceptedPaymentMethods,
        boolean negotiable,
        PackageRequestStatus statusBeforeRemoval,
        List<Photo> photos,
        List<Negotiation> negotiations,
        List<Report> reports,
        boolean canRemove,
        String removeBlockedReason,
        boolean canRestore
) {

    /** URL présignée à courte durée d'une photo du colis. */
    public record Photo(String url) {}

    public record Negotiation(
            UUID id,
            UUID travelerId,
            String travelerName,
            NegotiationThreadStatus status,
            BigDecimal lastPrice,
            String currency,
            LocalDateTime updatedAt
    ) {}

    /** {@code id} est celui du signalement dans la boîte générique (/admin/reports). */
    public record Report(
            UUID id,
            UUID reporterId,
            String reporterName,
            String reason,
            String details,
            String status,
            LocalDateTime createdAt
    ) {}
}

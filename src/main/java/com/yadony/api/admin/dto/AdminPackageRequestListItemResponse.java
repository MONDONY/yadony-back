package com.yadony.api.admin.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ligne de la liste admin des demandes d'envoi. {@code status} est le statut réel
 * (REMOVED_BY_ADMIN compris), contrairement au DTO de l'app mobile. {@code targetPrice} est
 * le budget net saisi par l'expéditeur, dans {@code currency}. {@code reportCount} compte les
 * signalements non supprimés de la boîte générique ; {@code openNegotiationCount} les fils
 * encore actifs.
 */
public record AdminPackageRequestListItemResponse(
        UUID id,
        UUID senderId,
        String senderName,
        String departureCity,
        String arrivalCity,
        LocalDate desiredDate,
        BigDecimal weightKg,
        ParcelSize parcelSize,
        TransportMode transportMode,
        PackageRequestStatus status,
        String currency,
        BigDecimal targetPrice,
        LocalDateTime createdAt,
        long reportCount,
        long openNegotiationCount
) {}

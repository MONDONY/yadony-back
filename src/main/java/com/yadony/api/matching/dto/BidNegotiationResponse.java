package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fil complet. {@code netEur} et {@code commissionEur} ne sont renseignés que pour
 * le voyageur (son net) et pour l'expéditeur (son détail) : la vue dépend du rôle,
 * calculée dans le service, jamais dans le client.
 */
public record BidNegotiationResponse(
        UUID bidId,
        UUID announcementId,
        String status,
        int round,
        int maxRounds,
        boolean myTurn,
        boolean canCounter,
        String currency,
        BigDecimal proposedGrossEur,
        BigDecimal netEur,
        BigDecimal commissionEur,
        BigDecimal suggestedGrossEur,
        BigDecimal weightKg,
        String description,
        String contentCategory,
        List<BidGridItemLine> gridItems,
        List<BidCustomItemResponse> customItems,
        List<String> photoUrls,
        String counterpartyName,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalDateTime expiresAt,
        List<BidNegotiationMessageResponse> messages
) {}

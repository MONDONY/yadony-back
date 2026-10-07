package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Ligne de la liste « Discussions de prix ». {@code role} vaut SENDER ou TRAVELER
 *  selon le point de vue du demandeur. */
public record BidNegotiationSummaryResponse(
        UUID bidId,
        UUID announcementId,
        String status,
        int round,
        boolean myTurn,
        boolean hasUnread,
        BigDecimal proposedGrossEur,
        String currency,
        String counterpartyName,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalDateTime updatedAt,
        String role,
        /** Rangée dans les « Archivées » du demandeur (FLUTTER-EJ), propre à chaque participant. */
        boolean archived
) {
    /** Constructeur de compatibilité (sans archived). */
    public BidNegotiationSummaryResponse(UUID bidId, UUID announcementId, String status, int round,
                                         boolean myTurn, boolean hasUnread, BigDecimal proposedGrossEur,
                                         String currency, String counterpartyName, String departureCity,
                                         String arrivalCity, LocalDate departureDate,
                                         LocalDateTime updatedAt, String role) {
        this(bidId, announcementId, status, round, myTurn, hasUnread, proposedGrossEur, currency,
                counterpartyName, departureCity, arrivalCity, departureDate, updatedAt, role, false);
    }
}

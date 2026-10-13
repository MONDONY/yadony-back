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
        boolean archived,
        /** Net voyageur du montant courant (FLUTTER-NS), servi au seul voyageur ; null pour
         *  l'expéditeur. Même calcul que {@code netEur} du fil. */
        BigDecimal proposedNetEur
) {
    /** Constructeur de compatibilité (sans net). */
    public BidNegotiationSummaryResponse(UUID bidId, UUID announcementId, String status, int round,
                                         boolean myTurn, boolean hasUnread, BigDecimal proposedGrossEur,
                                         String currency, String counterpartyName, String departureCity,
                                         String arrivalCity, LocalDate departureDate,
                                         LocalDateTime updatedAt, String role, boolean archived) {
        this(bidId, announcementId, status, round, myTurn, hasUnread, proposedGrossEur, currency,
                counterpartyName, departureCity, arrivalCity, departureDate, updatedAt, role, archived, null);
    }

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

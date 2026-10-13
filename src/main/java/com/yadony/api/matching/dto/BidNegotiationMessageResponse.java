package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Message sortant d'un fil de négociation de trajet.
 *
 * @param proposedNetEur net voyageur du montant proposé (FLUTTER-NS), servi au seul voyageur :
 *                       {@code null} (absent du JSON) pour l'expéditeur et pour un message
 *                       sans montant. Même calcul que {@code netEur} en tête du fil.
 */
public record BidNegotiationMessageResponse(
        UUID id, String kind, UUID authorId,
        BigDecimal proposedGrossEur, String body, LocalDateTime createdAt,
        BigDecimal proposedNetEur) {

    /** Ancienne arité, sans net. */
    public BidNegotiationMessageResponse(UUID id, String kind, UUID authorId,
                                         BigDecimal proposedGrossEur, String body, LocalDateTime createdAt) {
        this(id, kind, authorId, proposedGrossEur, body, createdAt, null);
    }
}

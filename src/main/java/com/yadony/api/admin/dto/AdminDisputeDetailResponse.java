package com.yadony.api.admin.dto;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminDisputeDetailResponse(
    UUID id, UUID bidId, String type, String status,
    String senderName, String travelerName,
    boolean refundFrozen, LocalDateTime createdAt,
    String resolution, OffsetDateTime resolvedAt, String resolutionNote,
    UUID beneficiaryUserId,
    /** Versement fonds de garantie : montant en centièmes et devise, null tant qu'aucun n'a été fait. */
    Long guaranteeAmountCents,
    String guaranteeCurrency,
    /** Parties du litige, pour désigner le bénéficiaire d'un fonds de garantie. */
    UUID senderId,
    UUID travelerId,
    /** Devise du colis (bid), celle d'un fonds de garantie ; null sans bid. */
    String bidCurrency,
    /** Partage décidé (V301, FLUTTER-E2), null sans partage. */
    java.math.BigDecimal senderRefundAmount,
    java.math.BigDecimal travelerPayoutAmount,
    String splitCurrency,
    /** Exécution du partage chez Stripe, null sans partage. */
    AdminDisputeSplitResponse split
) {}

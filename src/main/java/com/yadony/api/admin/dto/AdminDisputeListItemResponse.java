package com.yadony.api.admin.dto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminDisputeListItemResponse(
    UUID id, UUID bidId, String type, String status,
    String senderName, String travelerName,
    boolean refundFrozen, LocalDateTime createdAt,
    /** Montant du colis (paiement, à défaut prix négocié) et sa devise ; absents sans colis. */
    BigDecimal amount, String currency
) {}

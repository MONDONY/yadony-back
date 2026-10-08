package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/** Ce que l'admin peut répartir sur le colis d'un litige (formulaire de partage, FLUTTER-E2). */
public record AdminDisputeSplitOptionsResponse(
        boolean splittable,
        /** Code RFC 7807 de la raison du refus quand {@code splittable} est faux. */
        String reasonCode,
        String currency,
        BigDecimal amount,
        BigDecimal commission,
        BigDecimal refunded,
        BigDecimal netAvailable,
        String rail,
        String paymentStatus
) {}

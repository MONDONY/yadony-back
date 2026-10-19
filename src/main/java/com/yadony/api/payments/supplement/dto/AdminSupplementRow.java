package com.yadony.api.payments.supplement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un supplément vu par l'administration : montants complets (net, commission, brut) et références
 * prestataires, pour décider d'un supplément bloqué (litige tranché par partage, principal
 * remboursé). Le motif libre du voyageur n'y figure pas (données personnelles), seulement son code.
 */
public record AdminSupplementRow(
        UUID id,
        UUID bidId,
        UUID travelerId,
        UUID senderId,
        String rail,
        String status,
        String currency,
        BigDecimal netAmount,
        BigDecimal commissionAmount,
        BigDecimal grossAmount,
        String reasonCode,
        String closedReason,
        String commissionStatus,
        String commissionChargedVia,
        String photoUrl,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime paidAt,
        OffsetDateTime paidOutAt,
        OffsetDateTime refundedAt,
        String stripePaymentIntentId,
        String stripeChargeId,
        String stripeTransferId,
        String stripeRefundId,
        String commissionPaymentIntentId) {
}

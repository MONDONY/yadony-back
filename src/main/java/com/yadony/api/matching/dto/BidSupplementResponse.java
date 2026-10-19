package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Supplément de prix d'un colis, servi dans {@link BidResponse#supplement()} et par les endpoints
 * {@code /bids/{bidId}/supplements}. Montants dans la devise du colis.
 *
 * <p>SÉCURITÉ (règle métier) : l'expéditeur ne reçoit que le BRUT. {@code netAmount},
 * {@code commissionAmount} et {@code commissionStatus} sont nuls pour lui : avec deux des trois
 * montants, il retrouverait le net du voyageur par soustraction.
 *
 * @param status         {@code COMMISSION_PENDING}, {@code PROPOSED}, {@code PAYMENT_PENDING}, {@code PAID},
 *                       {@code PAYOUT_PENDING}, {@code PAID_OUT}, {@code DECLINED}, {@code EXPIRED},
 *                       {@code WITHDRAWN}, {@code CANCELLED}, {@code REFUND_PENDING}, {@code REFUNDED}.
 * @param rail           {@code STRIPE}, {@code PAWAPAY} ou {@code CASH} : celui du colis.
 * @param reasonCode     {@code GRID_ITEM}, {@code EXTRA_WEIGHT} ou {@code OTHER}.
 * @param photoUrl       URL présignée (1 h) de la photo du colis.
 * @param expiresAt      échéance de réponse de l'expéditeur (UTC).
 * @param blocksHandover vrai tant que la demande attend une réponse ou un paiement : le scan de
 *                       remise (DEPART) est refusé en 409 {@code handover-supplement-pending}.
 */
public record BidSupplementResponse(
        UUID id,
        String status,
        String rail,
        String currency,
        BigDecimal netAmount,
        BigDecimal commissionAmount,
        BigDecimal grossAmount,
        String reasonCode,
        String reasonText,
        String gridItemLabel,
        String photoUrl,
        OffsetDateTime expiresAt,
        OffsetDateTime paidAt,
        String commissionStatus,
        boolean blocksHandover
) {}

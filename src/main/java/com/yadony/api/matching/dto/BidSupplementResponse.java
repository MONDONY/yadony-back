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
 * @param commissionClientSecret rail espèces, voyageur seulement : client secret du PaymentIntent de
 *                       la commission du supplément quand la carte demande une authentification
 *                       (3DS, statut {@code COMMISSION_PENDING}). Renseigné uniquement dans la
 *                       réponse de la création et de {@code …/commission/confirm} ; nul partout ailleurs.
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
        boolean blocksHandover,
        String commissionClientSecret
) {

    /** Sans client secret de commission : toutes les vues sauf la création espèces en 3DS. */
    public BidSupplementResponse(UUID id, String status, String rail, String currency, BigDecimal netAmount,
                                 BigDecimal commissionAmount, BigDecimal grossAmount, String reasonCode,
                                 String reasonText, String gridItemLabel, String photoUrl, OffsetDateTime expiresAt,
                                 OffsetDateTime paidAt, String commissionStatus, boolean blocksHandover) {
        this(id, status, rail, currency, netAmount, commissionAmount, grossAmount, reasonCode, reasonText,
                gridItemLabel, photoUrl, expiresAt, paidAt, commissionStatus, blocksHandover, null);
    }

    /** Même vue, avec le client secret de la 3DS de la commission. */
    public BidSupplementResponse withCommissionClientSecret(String clientSecret) {
        return new BidSupplementResponse(id, status, rail, currency, netAmount, commissionAmount, grossAmount,
                reasonCode, reasonText, gridItemLabel, photoUrl, expiresAt, paidAt, commissionStatus, blocksHandover,
                clientSecret);
    }
}

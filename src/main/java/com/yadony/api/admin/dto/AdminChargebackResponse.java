package com.yadony.api.admin.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un litige bancaire, en lecture seule. {@code paymentId} est celui du litige, à défaut le paiement du colis ;
 * {@code trackingNumber}, {@code corridor} (« Paris → Dakar »), expéditeur et voyageur décrivent le colis. Un nom
 * est absent pour un compte supprimé (l'identifiant reste). {@code outcome} et {@code resolvedAt} ne sont
 * renseignés qu'une fois le litige tranché. Les champs nuls sont absents du JSON ({@code NON_NULL}).
 */
public record AdminChargebackResponse(
        UUID id,
        UUID bidId,
        long amountCents,
        /** Devise du litige Stripe (code ISO en majuscules) : un chargeback existe aussi en USD ou CAD. */
        String currency,
        String reason,
        String status,
        LocalDateTime openedAt,
        UUID paymentId,
        String trackingNumber,
        String corridor,
        UUID senderId,
        String senderName,
        UUID travelerId,
        String travelerName,
        String outcome,
        LocalDateTime resolvedAt
) {
}

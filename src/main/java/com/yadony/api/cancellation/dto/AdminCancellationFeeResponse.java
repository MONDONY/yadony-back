package com.yadony.api.cancellation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Retenue d'annulation vue par l'administration : lecture d'un colis et réponse des gestes. Les montants sont dans la
 * devise {@code currency}. {@code waivable} et {@code payoutRetryable} sont calculés ici : le front n'en recopie pas
 * les règles.
 *
 * @param status              DUE, RETAINED, PAYOUT_PENDING, PAID_OUT ou NOT_APPLICABLE
 * @param reason              motif de la retenue (SENDER_CANCEL, SENDER_NO_SHOW, SENDER_CANCEL_AFTER_HANDOVER)
 * @param rate                taux appliqué (0.3000 = 30 %)
 * @param payoutPendingReason dernier motif de l'attente de versement, ou {@code null}
 * @param waivable            vrai tant que la retenue est DUE : on peut y renoncer
 * @param payoutRetryable     vrai quand le versement au voyageur est PAYOUT_PENDING : on peut le relancer
 */
public record AdminCancellationFeeResponse(
        UUID id,
        UUID bidId,
        String status,
        String reason,
        BigDecimal rate,
        BigDecimal grossFee,
        BigDecimal refundFees,
        BigDecimal commission,
        BigDecimal travelerNet,
        String currency,
        String payoutRef,
        String payoutPendingReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean waivable,
        boolean payoutRetryable
) {
}

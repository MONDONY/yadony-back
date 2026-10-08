package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/**
 * Résolution d'un litige. {@code senderRefundAmount} et {@code travelerPayoutAmount} sont
 * facultatifs (FLUTTER-E2) : absents, la résolution n'enregistre que la décision, comme avant ;
 * présents (tous les deux, 0 accepté), le séquestre du colis est partagé dans la devise du
 * paiement, la somme ne dépassant pas le net disponible.
 */
public record AdminResolveDisputeRequest(
        String resolution,
        String note,
        BigDecimal senderRefundAmount,
        BigDecimal travelerPayoutAmount
) {
    public AdminResolveDisputeRequest(String resolution, String note) {
        this(resolution, note, null, null);
    }

    public boolean hasSplit() {
        return senderRefundAmount != null || travelerPayoutAmount != null;
    }
}

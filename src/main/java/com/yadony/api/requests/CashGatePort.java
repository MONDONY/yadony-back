package com.yadony.api.requests;

import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;

import java.math.BigDecimal;
import java.util.UUID;

public interface CashGatePort {
    /**
     * Returns true if the traveler's wallet balance IN {@code currency} covers the
     * commission amount. The commission is snapshotted in the request/thread's own
     * currency — comparing it against a wallet balance in a different currency would
     * silently under- or over-report available funds.
     */
    boolean hasSufficientFunds(UUID travelerId, BigDecimal commissionAmount, String currency);

    /**
     * Returns true if the traveler has a commission payment card registered.
     * Used at trip-linking time so a traveler whose wallet is short can still
     * link a CASH trip by consenting to a card charge (collected at finalize,
     * wallet-first then card).
     */
    boolean hasCommissionCard(UUID travelerId);

    /**
     * Charges Yadony's commission (netAmount × rate) from the traveler for a CASH
     * negotiated thread, wallet-first then card. Returns true if successfully
     * charged (or already charged — idempotent), false if it could not be charged.
     * Implementations MUST NOT throw on a normal decline — return false instead.
     */
    boolean chargeNegotiationCashCommission(java.util.UUID travelerId, java.util.UUID senderId, java.util.UUID threadId, java.math.BigDecimal netAmount);

    /**
     * Règle la commission Yadony d'un thread de négociation CASH à la demande du
     * voyageur. Le montant dérive du net négocié passé en paramètre. Ne lève jamais
     * sur un refus normal : le statut de la réponse porte l'issue.
     */
    AcceptBidResponse settleNegotiationCommission(
            UUID travelerId, UUID senderId, UUID threadId,
            BigDecimal netAmount, CommissionSource source);
}

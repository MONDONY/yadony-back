package com.yadony.api.payments.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Résultat du rejeu du ledger d'une devise (cf. {@link WalletRefundAllocator}) :
 * ce qui peut repartir vers Stripe recharge par recharge, ce qui est non-cash
 * (perdu à la suppression du compte) et ce qui est déjà en cours de remboursement.
 * Invariant : refundableTotal + nonRefundable + inFlight = solde du wallet.
 */
public record WalletRefundAllocation(List<RefundableTopup> refundable,
                                     BigDecimal refundableTotal,
                                     BigDecimal nonRefundable,
                                     BigDecimal inFlight) {

    public record RefundableTopup(UUID walletTransactionId, String paymentIntentId, BigDecimal remaining) {}

    public static WalletRefundAllocation empty() {
        return new WalletRefundAllocation(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

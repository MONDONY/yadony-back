package com.yadony.api.payments.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Résultat du rejeu du ledger d'une devise (cf. {@link WalletRefundAllocator}) :
 * ce qui peut repartir vers Stripe ou pawaPay recharge par recharge, ce qui est non-cash
 * (perdu à la suppression du compte) et ce qui est déjà en cours de remboursement.
 * Invariant : refundableTotal + nonRefundable + inFlight = solde du wallet.
 *
 * <p>{@code fees} et {@code net} portent les frais de remboursement (ex. frais pawaPay) et le
 * montant net après déduction. À ce stade (lot 2, tâche 1) ils valent respectivement
 * {@code ZERO} et {@code refundableTotal} : le calcul réel arrive en tâche 3.
 */
public record WalletRefundAllocation(List<RefundableTopup> refundable,
                                     BigDecimal refundableTotal,
                                     BigDecimal nonRefundable,
                                     BigDecimal inFlight,
                                     BigDecimal fees,
                                     BigDecimal net) {

    /**
     * Une recharge encore (partiellement) remboursable. {@code original} est le montant du
     * {@code TOP_UP} d'origine (avant toute dépense), {@code remaining} ce qu'il en reste.
     * {@code fee} et {@code provider} valent {@code ZERO}/{@code null} à ce stade (lot 2,
     * tâche 1) : la vraie valeur arrive en tâche 3, une fois {@code rail} exploité pour
     * retrouver l'opération pawaPay d'origine.
     */
    public record RefundableTopup(UUID walletTransactionId, String paymentIntentId, BigDecimal original,
                                  BigDecimal remaining, BigDecimal fee, WalletRefundRail rail, String provider) {}

    public static WalletRefundAllocation empty() {
        return new WalletRefundAllocation(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

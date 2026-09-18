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
 * montant net après déduction, calculés par {@link WalletRefundAllocator} via
 * {@code WalletRefundFeeCalculator.feeFor}. {@code fees} est la somme des {@code fee} des
 * recharges de {@code refundable} ; {@code net = refundableTotal - fees}.
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
     * {@code fee} est le frais retenu sur cette recharge si elle est remboursée (cf.
     * {@code WalletRefundFeeCalculator}), toujours plafonné à {@code remaining} : une cible
     * dont {@code remaining - fee <= 0} n'a rien à verser (l'appelant, ex.
     * {@code WalletSelfRefundService#request}, l'exclut de ses cibles). {@code provider} est
     * l'opérateur pawaPay de la recharge quand {@code rail == PAWAPAY}, {@code null} sinon.
     */
    public record RefundableTopup(UUID walletTransactionId, String paymentIntentId, BigDecimal original,
                                  BigDecimal remaining, BigDecimal fee, WalletRefundRail rail, String provider) {}

    public static WalletRefundAllocation empty() {
        return new WalletRefundAllocation(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

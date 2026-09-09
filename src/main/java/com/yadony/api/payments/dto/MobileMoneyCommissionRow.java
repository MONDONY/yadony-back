package com.yadony.api.payments.dto;

import com.yadony.api.payments.PaymentStatus;
import java.math.BigDecimal;

/**
 * Une ligne d'agrégat des commissions mobile money : un couple (devise, statut) sur la période.
 *
 * <p>Jamais de total toutes devises confondues : additionner des XOF et des EUR ne veut rien
 * dire (même raison que {@code CurrencyAmountRow}). Le regroupement par statut porte le sens
 * comptable : {@code RELEASED} = commission acquise (le voyageur a été versé, la part yadony
 * est restée sur le solde pawaPay), {@code ESCROW} = commission encore conditionnelle
 * (livraison non confirmée), {@code REFUNDED} = commission rendue avec le remboursement.
 *
 * @param currency devise en majuscules
 * @param status statut du paiement
 * @param count nombre de paiements
 * @param gross somme des montants payés par les expéditeurs
 * @param commission somme des commissions yadony ; le net versé au voyageur est la différence
 */
public record MobileMoneyCommissionRow(
        String currency,
        PaymentStatus status,
        long count,
        BigDecimal gross,
        BigDecimal commission) {
}

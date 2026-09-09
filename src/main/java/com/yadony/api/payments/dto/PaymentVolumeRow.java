package com.yadony.api.payments.dto;

import com.yadony.api.payments.PaymentStatus;
import java.math.BigDecimal;

/**
 * Une ligne des volumes de la vue d'ensemble admin : un couple (devise, statut).
 *
 * <p>Jamais de total toutes devises confondues : la vue d'ensemble additionnait des EUR, des XOF
 * et des XAF dans un seul {@code SUM} et affichait le résultat en euros (même bug que
 * {@link CurrencyAmountRow}). Chaque indicateur se lit sur un statut : séquestre détenu
 * ({@code ESCROW}), libéré et commission acquise ({@code RELEASED}), remboursé ({@code REFUNDED}).
 *
 * @param currency devise en majuscules
 * @param status statut du paiement
 * @param amount somme des montants payés par les expéditeurs
 * @param commission somme des commissions yadony
 * @param refunded somme des montants remboursés
 */
public record PaymentVolumeRow(
        String currency,
        PaymentStatus status,
        BigDecimal amount,
        BigDecimal commission,
        BigDecimal refunded) {
}

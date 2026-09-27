package com.yadony.api.payments.wallet;

import java.math.BigDecimal;

/**
 * Un portefeuille tel que l'admin le voit.
 *
 * @param refundEligibleAmount part du solde encore remboursable vers la carte ou le mobile
 *                             money d'origine, issue du rejeu du ledger
 *                             ({@link WalletRefundAllocation#refundableTotal()}) et non de la
 *                             colonne {@code refund_eligible_amount}, que l'application ne lit
 *                             plus depuis V258. {@code null} quand le rejeu ne retombe pas sur
 *                             le solde (ledger incohérent, alerte admin déjà levée).
 * @param frozen               une demande de remboursement PENDING/PROCESSING gèle la devise
 */
public record WalletAccountView(String currency, BigDecimal balance, BigDecimal refundEligibleAmount,
                                boolean frozen) {
}

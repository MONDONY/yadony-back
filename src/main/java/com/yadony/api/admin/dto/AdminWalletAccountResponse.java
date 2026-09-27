package com.yadony.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.yadony.api.payments.wallet.WalletAccountView;

import java.math.BigDecimal;

/**
 * Un portefeuille d'un utilisateur, vu depuis sa fiche admin. Montants en unités de la
 * devise (12.50, 1500), pas en centimes : c'est le contrat de l'écran de correction de
 * solde, distinct de {@link AdminWalletResponse} (liste finance, en centimes).
 *
 * @param refundEligibleAmount part remboursable issue du rejeu du ledger ; {@code null} si
 *                             ce rejeu est incohérent
 */
// Clés toujours présentes, à null si besoin : contrat de l'écran admin, quelle que soit
// l'inclusion Jackson globale (NON_NULL).
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminWalletAccountResponse(String currency, BigDecimal balance, BigDecimal refundEligibleAmount,
                                         boolean frozen) {

    public static AdminWalletAccountResponse from(WalletAccountView view) {
        return new AdminWalletAccountResponse(view.currency(), view.balance(), view.refundEligibleAmount(),
                view.frozen());
    }
}

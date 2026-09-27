package com.yadony.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Réponse d'une correction : le portefeuille après coup et le mouvement écrit (ou rejoué). */
// Clés toujours présentes, à null si besoin : contrat de l'écran admin, quelle que soit
// l'inclusion Jackson globale (NON_NULL).
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminWalletAdjustmentResponse(AdminWalletAccountResponse account,
                                            AdminWalletTransactionResponse transaction) {
}

package com.yadony.api.admin.dto;

/**
 * Nombres par vue de la liste mobile money (mêmes {@code kind}, {@code provider} et {@code q} que la liste, sans
 * {@code view}). {@code attention} = versements et remboursements de paiement en échec non remplacés, même règle
 * que l'alerte {@code MM_FAILED} des paiements, comptée PAR OPÉRATION : un paiement à deux versements morts
 * successifs compte 2, le nombre peut donc dépasser {@code alerts.mmFailed} des paiements, qui compte des paiements.
 */
public record AdminMobileMoneyCountsResponse(long failed, long open, long done, long all, long attention) {
}

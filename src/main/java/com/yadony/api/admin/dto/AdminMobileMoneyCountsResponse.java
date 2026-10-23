package com.yadony.api.admin.dto;

/**
 * Nombres par vue de la liste mobile money (mêmes {@code kind}, {@code provider} et {@code q} que la liste, sans
 * {@code view}). {@code attention} = versements et remboursements de paiement en échec non remplacés, même règle
 * que l'alerte {@code MM_FAILED} des paiements, comptée par opération.
 */
public record AdminMobileMoneyCountsResponse(long failed, long open, long done, long all, long attention) {
}

package com.yadony.api.admin.dto;

/**
 * Nombres des commissions sur règlement en espèces : {@code attention} = {@code FAILED}, {@code REQUIRES_3DS} et
 * {@code REFUND_FAILED}, {@code all} = toutes. Ils suivent {@code q} et {@code partyId}, jamais la vue ni le statut.
 */
public record AdminCashCommissionCountsResponse(long attention, long all) {
}

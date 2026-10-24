package com.yadony.api.admin.dto;

/**
 * Nombres des litiges bancaires par statut ({@code OPEN}, {@code WON}, {@code LOST}) et {@code all}. Ils suivent
 * {@code q}, jamais la vue.
 */
public record AdminChargebackCountsResponse(long open, long won, long lost, long all) {
}

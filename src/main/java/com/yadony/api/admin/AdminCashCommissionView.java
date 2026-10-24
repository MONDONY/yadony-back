package com.yadony.api.admin;

/**
 * Vues rapides de la liste des commissions cash ; {@code ATTENTION} = {@code FAILED}, {@code REQUIRES_3DS} et
 * {@code REFUND_FAILED} ({@link AdminFinanceSql#CASH_ATTENTION}).
 */
public enum AdminCashCommissionView { ALL, ATTENTION }

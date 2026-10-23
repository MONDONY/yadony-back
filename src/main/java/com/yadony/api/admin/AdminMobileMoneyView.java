package com.yadony.api.admin;

/**
 * Vues rapides de la liste mobile money de l'admin ; définitions dans {@link AdminFinanceSql#MM_VIEW_PREDICATE} :
 * {@code FAILED} = FAILED ou SUBMIT_REJECTED, {@code OPEN} = non finales, {@code DONE} = COMPLETED.
 */
public enum AdminMobileMoneyView { ALL, FAILED, OPEN, DONE }

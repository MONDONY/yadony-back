package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/**
 * Corps de {@code POST /admin/users/{userId}/wallet/adjustments}. Validé côté service
 * (bornes dépendantes de la devise) : {@code direction} vaut CREDIT ou DEBIT, {@code reason}
 * fait 10 à 500 caractères après trim.
 */
public record AdminWalletAdjustmentRequest(String currency, String direction, BigDecimal amount, String reason) {
}

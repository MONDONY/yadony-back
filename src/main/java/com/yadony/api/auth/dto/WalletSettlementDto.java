package com.yadony.api.auth.dto;

import java.math.BigDecimal;

/**
 * Ce qu'il advient d'un solde wallet à la suppression du compte, par devise :
 * {@code refundableAmount} repart vers l'utilisateur par {@code rail} (STRIPE automatique
 * ou MANUAL par ticket admin), {@code forfeitedAmount} (non-cash) est perdu à la
 * finalisation, {@code inFlightAmount} est déjà en cours de remboursement.
 */
public record WalletSettlementDto(String currency,
                                  BigDecimal refundableAmount,
                                  BigDecimal forfeitedAmount,
                                  BigDecimal inFlightAmount,
                                  String rail) {
    public static final String RAIL_STRIPE = "STRIPE";
    public static final String RAIL_MANUAL = "MANUAL";
}

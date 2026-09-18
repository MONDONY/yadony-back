package com.yadony.api.auth.dto;

import java.math.BigDecimal;

/**
 * Ce qu'il advient d'un solde wallet à la suppression du compte, par devise :
 * {@code refundableAmount} (brut) repart vers l'utilisateur par {@code rail} (STRIPE ou PAWAPAY
 * automatiques, MANUAL par ticket admin), {@code forfeitedAmount} (non-cash) est perdu à la
 * finalisation, {@code inFlightAmount} est déjà en cours de remboursement.
 *
 * <p>Champs additifs : {@code feeAmount} retenu au remboursement, {@code netAmount} réellement
 * versé ({@code refundableAmount - feeAmount} ; rail MANUAL : frais nuls, net = solde) et
 * {@code destinationMasked}, numéro masqué du dépôt mobile money de la première cible pawaPay
 * ({@code null} sinon, jamais le numéro en clair).
 */
public record WalletSettlementDto(String currency,
                                  BigDecimal refundableAmount,
                                  BigDecimal forfeitedAmount,
                                  BigDecimal inFlightAmount,
                                  String rail,
                                  BigDecimal feeAmount,
                                  BigDecimal netAmount,
                                  String destinationMasked) {
    public static final String RAIL_STRIPE = "STRIPE";
    public static final String RAIL_PAWAPAY = "PAWAPAY";
    public static final String RAIL_MANUAL = "MANUAL";
}

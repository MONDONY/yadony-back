package com.yadony.api.payments.cash.dto;

import com.yadony.api.payments.cash.CommissionSplit;

import java.math.BigDecimal;

/**
 * Détail d'un « solde insuffisant » quand la devise du colis diffère de la devise active :
 * ce que le portefeuille de la devise du colis couvre, ce qui manque (dans les deux devises)
 * et le solde actif. Absent (null) quand les deux devises sont identiques.
 */
public record CommissionShortfallDto(String bidCurrency,
                                     BigDecimal commission,
                                     BigDecimal coveredByBidWallet,
                                     BigDecimal remainingBid,
                                     BigDecimal remainingInActive,
                                     String activeCurrency,
                                     BigDecimal activeBalance) {

    public static CommissionShortfallDto from(CommissionSplit s) {
        if (s.sameCurrency()) {
            return null;
        }
        return new CommissionShortfallDto(s.bidCurrency(), s.commission(), s.fromBidWallet(),
                s.remainingBid(), s.remainingActive(), s.activeCurrency(), s.activeBalance());
    }
}

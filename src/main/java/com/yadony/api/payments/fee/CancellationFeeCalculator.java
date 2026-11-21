package com.yadony.api.payments.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calcul pur (sans I/O) du dédommagement d'annulation côté paiements. */
public final class CancellationFeeCalculator {

    private CancellationFeeCalculator() {}

    /**
     * @param paidAmount            montant payé par l'expéditeur (> 0)
     * @param rate                  taux de dédommagement, fraction dans [0, 1]
     * @param capInPaymentCurrency  plafond déjà converti dans la devise du paiement (>= 0)
     * @param refundFees            frais de remboursement (>= 0)
     * @param commissionRate        commission en fraction dans [0, 1] (0,05 = 5 %)
     * @param scale                 décimales de la devise (2 pour EUR, 0 pour XOF)
     */
    public static CancellationFeeBreakdown compute(
            BigDecimal paidAmount,
            BigDecimal rate,
            BigDecimal capInPaymentCurrency,
            BigDecimal refundFees,
            BigDecimal commissionRate,
            int scale) {
        require(paidAmount != null && paidAmount.signum() > 0, "paidAmount doit être strictement positif");
        require(rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0,
                "rate doit être compris entre 0 et 1");
        require(capInPaymentCurrency != null && capInPaymentCurrency.signum() >= 0,
                "capInPaymentCurrency ne peut pas être négatif");
        require(refundFees != null && refundFees.signum() >= 0, "refundFees ne peut pas être négatif");
        require(commissionRate != null && commissionRate.signum() >= 0
                        && commissionRate.compareTo(BigDecimal.ONE) <= 0,
                "commissionRate doit être compris entre 0 et 1");
        require(scale >= 0, "scale ne peut pas être négatif");

        BigDecimal gross = paidAmount.multiply(rate).min(capInPaymentCurrency).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal fees = refundFees.setScale(scale, RoundingMode.HALF_UP);
        BigDecimal afterFees = gross.subtract(fees).max(BigDecimal.ZERO.setScale(scale));
        BigDecimal commission = afterFees.multiply(commissionRate).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal travelerNet = afterFees.subtract(commission);
        BigDecimal senderRefund = paidAmount.setScale(scale, RoundingMode.HALF_UP).subtract(gross);
        return new CancellationFeeBreakdown(gross, fees, commission, travelerNet, senderRefund);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}

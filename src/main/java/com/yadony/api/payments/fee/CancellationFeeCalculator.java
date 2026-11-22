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

        BigDecimal gross = grossFee(paidAmount, rate, capInPaymentCurrency, scale);
        BigDecimal fees = refundFees.setScale(scale, RoundingMode.HALF_UP);
        BigDecimal afterFees = gross.subtract(fees).max(BigDecimal.ZERO.setScale(scale));
        BigDecimal commission = afterFees.multiply(commissionRate).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal travelerNet = afterFees.subtract(commission);
        BigDecimal senderRefund = senderRefund(paidAmount, gross, scale);
        return new CancellationFeeBreakdown(gross, fees, commission, travelerNet, senderRefund);
    }

    /**
     * Frais BRUTS retenus sur le remboursement : {@code min(payé × taux, plafond)} à l'échelle de la devise. Seule
     * formule du brut : {@link #compute} la prend, l'aperçu montré à l'expéditeur avant d'annuler aussi.
     */
    public static BigDecimal grossFee(BigDecimal paidAmount, BigDecimal rate, BigDecimal capInPaymentCurrency, int scale) {
        require(paidAmount != null && paidAmount.signum() > 0, "paidAmount doit être strictement positif");
        require(rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0,
                "rate doit être compris entre 0 et 1");
        require(capInPaymentCurrency != null && capInPaymentCurrency.signum() >= 0,
                "capInPaymentCurrency ne peut pas être négatif");
        require(scale >= 0, "scale ne peut pas être négatif");
        return paidAmount.multiply(rate).min(capInPaymentCurrency).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Le plafond a-t-il limité le brut ? Vrai exactement quand {@code payé × taux} arrondi à l'échelle de la devise
     * dépasse le brut retenu par {@link #grossFee} (donc le plafond). Faux quand le brut est nul (aucun frais). Même
     * entrées et même brut que le règlement : l'aperçu et le remboursement ne peuvent pas diverger.
     */
    public static boolean isCapped(BigDecimal paidAmount, BigDecimal rate, BigDecimal capInPaymentCurrency, int scale) {
        BigDecimal gross = grossFee(paidAmount, rate, capInPaymentCurrency, scale);
        BigDecimal uncapped = paidAmount.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
        return gross.signum() > 0 && gross.compareTo(uncapped) < 0;
    }

    /** Rendu à l'expéditeur : le payé, à l'échelle de la devise, moins le brut de {@link #grossFee}. */
    public static BigDecimal senderRefund(BigDecimal paidAmount, BigDecimal grossFee, int scale) {
        return paidAmount.setScale(scale, RoundingMode.HALF_UP).subtract(grossFee);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}

package com.yadony.api.payments.wallet.fees;

import com.yadony.api.payments.currency.SupportedCurrency;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Montant qu'un remboursement ou un versement pawaPay peut envoyer sans échouer en
 * {@code PAWAPAY_WALLET_OUT_OF_FUNDS}. pawaPay débite du wallet Yadony le montant envoyé PLUS ses
 * frais, en lignes séparées : pour envoyer {@code x}, le wallet doit tenir
 * {@code x × (1 + taux / 100)}, où {@code taux} est le {@code refund-percent} de l'opérateur
 * ({@link PawapayFeeTable}) augmenté d'une marge de sécurité
 * ({@code yadony.pawapay.refund-safety-margin-percent}).
 *
 * <p>Cas réel du 18/10 : recharge de 500 XOF Orange CI, wallet crédité de 487,50, taux
 * 2,0 + 1,0 = 3 %. Montant sûr = floor(487,50 / 1,03) = 473 ; le dû (500 - 23 de frais) est
 * de 477, il manque ceil(477 × 1,03 - 487,50) = 4 XOF au wallet pour le verser.
 *
 * <p>Pur : aucune dépendance Spring ni appel réseau.
 */
public final class PawapayRefundSafeAmount {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PawapayRefundSafeAmount() {
    }

    /** Taux total retenu par le calcul : {@code refundPercent + marginPercent}, nuls lus comme zéro. */
    public static BigDecimal ratePercent(BigDecimal refundPercent, BigDecimal marginPercent) {
        return nz(refundPercent).add(nz(marginPercent));
    }

    /**
     * {@code floor(walletBalance / (1 + ratePercent / 100))} à l'échelle de la devise (0 décimale
     * pour XOF et XAF, 2 pour EUR), arrondi vers le bas ; zéro pour un solde nul ou négatif.
     */
    public static BigDecimal maxSafe(BigDecimal walletBalance, BigDecimal ratePercent, String currency) {
        Objects.requireNonNull(walletBalance, "walletBalance");
        int scale = scale(currency);
        if (walletBalance.signum() <= 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return walletBalance.divide(factor(ratePercent), MathContext.DECIMAL64).setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Ce qu'il manque au wallet pour envoyer {@code amount} en sécurité :
     * {@code ceil(amount × (1 + ratePercent / 100) - walletBalance)} à l'échelle de la devise,
     * zéro si le solde suffit déjà.
     */
    public static BigDecimal topUpNeeded(BigDecimal amount, BigDecimal walletBalance, BigDecimal ratePercent,
                                         String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(walletBalance, "walletBalance");
        int scale = scale(currency);
        BigDecimal missing = amount.multiply(factor(ratePercent)).subtract(walletBalance);
        if (missing.signum() <= 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return missing.setScale(scale, RoundingMode.UP);
    }

    private static BigDecimal factor(BigDecimal ratePercent) {
        BigDecimal rate = nz(ratePercent);
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("Taux de frais négatif : " + rate.toPlainString());
        }
        return BigDecimal.ONE.add(rate.divide(HUNDRED, MathContext.DECIMAL64));
    }

    private static int scale(String currency) {
        return SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

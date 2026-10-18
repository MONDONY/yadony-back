package com.yadony.api.payments.wallet.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PawapayRefundSafeAmountTest {

    private static final BigDecimal RATE_ORANGE_CIV = PawapayRefundSafeAmount.ratePercent(
            new BigDecimal("2.0"), new BigDecimal("1.0"));

    @Test
    @DisplayName("Cas réel du 18/10 : 500 XOF Orange CI, wallet à 487,50, taux 2 + 1 → sûr 473, dû 477, manque 4")
    void casReel_500Xof_wallet487_50() {
        PawapayFeeTable table = new PawapayFeeTable(new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0")),
                Map.of("ORANGE_CIV", new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0"))));
        BigDecimal due = new BigDecimal("500").subtract(table.fee("ORANGE_CIV", new BigDecimal("500"), "XOF"));
        BigDecimal balance = new BigDecimal("487.50");

        BigDecimal maxSafe = PawapayRefundSafeAmount.maxSafe(balance, RATE_ORANGE_CIV, "XOF");

        assertThat(RATE_ORANGE_CIV).isEqualByComparingTo("3.0");
        assertThat(maxSafe).isEqualTo(new BigDecimal("473"));
        assertThat(due).isEqualByComparingTo("477");
        assertThat(due.min(maxSafe)).isEqualByComparingTo("473");
        assertThat(PawapayRefundSafeAmount.topUpNeeded(due, balance, RATE_ORANGE_CIV, "XOF"))
                .isEqualTo(new BigDecimal("4"));
        // Le manque suffit : avec 4 XOF de plus, le dû passe en sécurité.
        assertThat(PawapayRefundSafeAmount.maxSafe(balance.add(new BigDecimal("4")), RATE_ORANGE_CIV, "XOF"))
                .isGreaterThanOrEqualTo(due);
        // Le montant sûr, lui, ne demande rien de plus.
        assertThat(PawapayRefundSafeAmount.topUpNeeded(maxSafe, balance, RATE_ORANGE_CIV, "XOF")).isZero();
    }

    @Test
    @DisplayName("EUR : 2 décimales, arrondi vers le bas pour le sûr et vers le haut pour le manque")
    void eur_deuxDecimales() {
        BigDecimal rate = new BigDecimal("3");
        assertThat(PawapayRefundSafeAmount.maxSafe(new BigDecimal("100"), rate, "EUR"))
                .isEqualTo(new BigDecimal("97.08"));
        assertThat(PawapayRefundSafeAmount.topUpNeeded(new BigDecimal("98.00"), new BigDecimal("100"), rate, "EUR"))
                .isEqualTo(new BigDecimal("0.94"));
    }

    @Test
    @DisplayName("Solde nul ou négatif : rien de sûr, le manque vaut tout le coût du dû")
    void soldeNul() {
        assertThat(PawapayRefundSafeAmount.maxSafe(BigDecimal.ZERO, RATE_ORANGE_CIV, "XOF")).isZero();
        assertThat(PawapayRefundSafeAmount.maxSafe(new BigDecimal("-12"), RATE_ORANGE_CIV, "XOF")).isZero();
        assertThat(PawapayRefundSafeAmount.topUpNeeded(new BigDecimal("477"), BigDecimal.ZERO, RATE_ORANGE_CIV, "XOF"))
                .isEqualByComparingTo("492");
    }

    @Test
    @DisplayName("Taux nuls lus comme zéro, taux négatif refusé")
    void taux() {
        assertThat(PawapayRefundSafeAmount.ratePercent(null, null)).isZero();
        assertThat(PawapayRefundSafeAmount.maxSafe(new BigDecimal("487.50"), null, "XOF")).isEqualByComparingTo("487");
        assertThatThrownBy(() -> PawapayRefundSafeAmount.maxSafe(BigDecimal.TEN, new BigDecimal("-1"), "XOF"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Barème : rateFor rend la surcharge de l'opérateur, sinon le défaut")
    void rateFor() {
        PawapayFeeTable table = new PawapayFeeTable(new PawapayFeeTable.Rate(BigDecimal.ONE, new BigDecimal("2.0")),
                Map.of("MTN_MOMO_CIV", new PawapayFeeTable.Rate(new BigDecimal("1.8"), new BigDecimal("1.3"))));
        assertThat(table.rateFor("MTN_MOMO_CIV").refundPercent()).isEqualByComparingTo("1.3");
        assertThat(table.rateFor("WAVE_SEN").refundPercent()).isEqualByComparingTo("2.0");
        assertThat(new PawapayFeeTable(null, null).rateFor(null).refundPercent()).isEqualByComparingTo("2.0");
    }
}

package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WalletAmountTextTest {

    @Test
    void xof_hasNoDecimalsAndANonBreakingThousandsSeparator() {
        assertThat(WalletAmountText.format(new BigDecimal("1000000"), "XOF"))
                .isEqualTo("1 000 000 F CFA");
        assertThat(WalletAmountText.format(new BigDecimal("500"), "XOF")).isEqualTo("500 F CFA");
    }

    @Test
    void xof_roundsToTheUnit_likeCurrencyAmount() {
        assertThat(WalletAmountText.format(new BigDecimal("1000.50"), "XOF")).isEqualTo("1 001 F CFA");
    }

    @Test
    void eur_keepsTwoDecimalsWithAFrenchComma() {
        assertThat(WalletAmountText.format(new BigDecimal("12.5"), "EUR")).isEqualTo("12,50 €");
        assertThat(WalletAmountText.format(new BigDecimal("1234"), "eur")).isEqualTo("1 234,00 €");
    }

    @Test
    void unknownCurrency_fallsBackToEuro_likeSupportedCurrency() {
        assertThat(WalletAmountText.format(new BigDecimal("10"), "ZZZ")).isEqualTo("10,00 €");
    }
}

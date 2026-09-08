package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PawapayAmountsTest {

    @Test
    void xof_hasNoDecimals() {
        assertThat(PawapayAmounts.format(new BigDecimal("15000"), "XOF")).isEqualTo("15000");
        assertThat(PawapayAmounts.format(new BigDecimal("15000.00"), "XOF")).isEqualTo("15000");
        assertThat(PawapayAmounts.format(new BigDecimal("15000.50"), "XAF")).isEqualTo("15001");
    }

    @Test
    void twoDecimalCurrency_keepsTwoDecimals() {
        assertThat(PawapayAmounts.format(new BigDecimal("12.5"), "EUR")).isEqualTo("12.50");
    }
}

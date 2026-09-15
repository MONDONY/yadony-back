package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WalletAccountEntityTest {

    @Test
    void defaults_soldeZeroDeviseEur() {
        WalletAccountEntity w = new WalletAccountEntity();
        assertThat(w.getBalance()).isEqualByComparingTo("0");
        assertThat(w.getCurrency()).isEqualTo("EUR");
        assertThat(w.getRefundEligibleAmount()).isEqualByComparingTo("0");
    }
}

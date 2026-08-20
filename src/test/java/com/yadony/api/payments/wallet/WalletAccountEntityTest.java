package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WalletAccountEntityTest {

    @Test
    void isRefundEligible_falseWhenBalanceZero() {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setRefundEligibleAmount(BigDecimal.ZERO);

        assertThat(wallet.isRefundEligible()).isFalse();
    }

    @Test
    void isRefundEligible_falseWhenRefundEligibleAmountLowerThanBalance() {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setBalance(new BigDecimal("30.00"));
        wallet.setRefundEligibleAmount(new BigDecimal("10.00"));

        assertThat(wallet.isRefundEligible()).isFalse();
    }

    @Test
    void isRefundEligible_trueWhenRefundEligibleAmountEqualsBalance() {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setBalance(new BigDecimal("30.00"));
        wallet.setRefundEligibleAmount(new BigDecimal("30.00"));
        wallet.setRefundEligibleSince(Instant.now());

        assertThat(wallet.isRefundEligible()).isTrue();
    }
}

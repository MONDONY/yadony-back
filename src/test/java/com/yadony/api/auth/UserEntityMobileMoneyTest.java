package com.yadony.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserEntityMobileMoneyTest {

    @Test
    void newUser_isNotConfigured_andHasNoActiveMobileMoney() {
        UserEntity user = new UserEntity();
        assertThat(user.getMobileMoneyStatus()).isEqualTo(MobileMoneyPayoutStatus.NOT_CONFIGURED);
        assertThat(user.hasActiveMobileMoney()).isFalse();
    }

    @Test
    void hasActiveMobileMoney_onlyWhenActive() {
        UserEntity user = new UserEntity();
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        assertThat(user.hasActiveMobileMoney()).isTrue();
        user.setMobileMoneyStatus(MobileMoneyPayoutStatus.DISABLED);
        assertThat(user.hasActiveMobileMoney()).isFalse();
    }

    @Test
    void isMobileMoney_onlyForTheNewEnumValue() {
        assertThat(com.yadony.api.payments.cash.PaymentMethod.MOBILE_MONEY.isMobileMoney()).isTrue();
        assertThat(com.yadony.api.payments.cash.PaymentMethod.WAVE.isMobileMoney()).isFalse();
        assertThat(com.yadony.api.payments.cash.PaymentMethod.ORANGE_MONEY.isMobileMoney()).isFalse();
        assertThat(com.yadony.api.payments.cash.PaymentMethod.STRIPE.isMobileMoney()).isFalse();
        assertThat(com.yadony.api.payments.cash.PaymentMethod.CASH.isMobileMoney()).isFalse();
    }
}

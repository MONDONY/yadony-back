package com.yadony.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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


    private UserEntity user(MobileMoneyPayoutStatus status, String currencies, String legacyCurrency) {
        UserEntity u = new UserEntity();
        u.setMobileMoneyStatus(status);
        org.springframework.test.util.ReflectionTestUtils.setField(u, "mobileMoneyCurrencies", currencies);
        u.setMobileMoneyCurrency(legacyCurrency);
        return u;
    }

    @Test
    void canReceive_isTrueForAnyCurrencyOfTheList_whenActive() {
        UserEntity u = user(MobileMoneyPayoutStatus.ACTIVE, "XOF,XAF", "XOF");
        assertThat(u.canReceiveMobileMoney("XAF")).isTrue();
        assertThat(u.canReceiveMobileMoney("xof")).isTrue();
        assertThat(u.canReceiveMobileMoney("GNF")).isFalse();
    }

    @Test
    void canReceive_isFalse_whenStatusIsNotActive() {
        UserEntity u = user(MobileMoneyPayoutStatus.DISABLED, "XOF,XAF", "XOF");
        assertThat(u.canReceiveMobileMoney("XAF")).isFalse();
    }

    /** Plus de repli sur la devise du compte le plus ancien : sans liste, aucune devise (V343 a désactivé ces cas). */
    @Test
    void canReceive_isFalse_whenTheListIsEmpty_evenWithTheLegacyCurrency() {
        assertThat(user(MobileMoneyPayoutStatus.ACTIVE, null, "XOF").canReceiveMobileMoney("XOF")).isFalse();
        assertThat(user(MobileMoneyPayoutStatus.ACTIVE, " ", "XOF").canReceiveMobileMoney("XOF")).isFalse();
        assertThat(user(MobileMoneyPayoutStatus.ACTIVE, null, null).canReceiveMobileMoney("XOF")).isFalse();
    }

    @Test
    void canReceive_isFalse_forANullCurrency() {
        assertThat(user(MobileMoneyPayoutStatus.ACTIVE, "XOF", "XOF").canReceiveMobileMoney(null)).isFalse();
    }

    @Test
    void currencyList_roundTripsThroughTheCsvColumn() {
        UserEntity u = new UserEntity();
        assertThat(u.getMobileMoneyCurrencyList()).isEmpty();

        u.setMobileMoneyCurrencyList(List.of("XOF", "XAF"));
        assertThat(csv(u)).isEqualTo("XOF,XAF");
        assertThat(u.getMobileMoneyCurrencyList()).containsExactly("XOF", "XAF");

        org.springframework.test.util.ReflectionTestUtils.setField(u, "mobileMoneyCurrencies", "XOF, ,XAF");
        assertThat(u.getMobileMoneyCurrencyList()).containsExactly("XOF", "XAF");

        u.setMobileMoneyCurrencyList(List.of());
        assertThat(csv(u)).isNull();
        u.setMobileMoneyCurrencyList(null);
        assertThat(csv(u)).isNull();
    }

    private static Object csv(UserEntity u) {
        return org.springframework.test.util.ReflectionTestUtils.getField(u, "mobileMoneyCurrencies");
    }
}

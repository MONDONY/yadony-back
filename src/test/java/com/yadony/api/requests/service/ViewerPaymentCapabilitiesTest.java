package com.yadony.api.requests.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewerPaymentCapabilitiesTest {

    @Test
    void of_null_isNone() {
        assertThat(ViewerPaymentCapabilities.of(null)).isSameAs(ViewerPaymentCapabilities.NONE);
        assertThat(ViewerPaymentCapabilities.NONE.canReceiveMobileMoney("XOF")).isFalse();
        assertThat(new ViewerPaymentCapabilities(false, null).canReceiveMobileMoney("XOF")).isFalse();
    }

    @Test
    void of_activeAccount_keepsCurrencyAndMatchesCaseInsensitively() {
        UserEntity u = new UserEntity();
        u.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        u.setMobileMoneyCurrency("XOF");
        u.setMobileMoneyCurrencyList(List.of("XOF"));
        ViewerPaymentCapabilities caps = ViewerPaymentCapabilities.of(u);
        assertThat(caps.hasConnect()).isFalse();
        assertThat(caps.canReceiveMobileMoney("xof")).isTrue();
        assertThat(caps.canReceiveMobileMoney("XAF")).isFalse();
        assertThat(caps.canReceiveMobileMoney(null)).isFalse();
    }

    @Test
    void of_accountsInTwoCurrencies_receivesInEach_notOnlyTheOldestAccountsCurrency() {
        UserEntity u = new UserEntity();
        u.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        u.setMobileMoneyCurrency("XOF"); // compte le plus ancien
        u.setMobileMoneyCurrencyList(List.of("XOF", "XAF"));
        ViewerPaymentCapabilities caps = ViewerPaymentCapabilities.of(u);
        assertThat(caps.canReceiveMobileMoney("XAF")).isTrue();
        assertThat(caps.canReceiveMobileMoney("xof")).isTrue();
        assertThat(caps.canReceiveMobileMoney("EUR")).isFalse();
    }

    @Test
    void of_inactiveAccount_dropsCurrency() {
        UserEntity u = new UserEntity();
        u.setMobileMoneyStatus(MobileMoneyPayoutStatus.DISABLED); // n'importe quel statut non ACTIVE existant
        u.setMobileMoneyCurrencyList(List.of("XOF"));
        assertThat(ViewerPaymentCapabilities.of(u).canReceiveMobileMoney("XOF")).isFalse();
    }
}

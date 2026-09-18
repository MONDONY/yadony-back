package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletRefundRailTest {

    @Test
    void stripePaymentIntent() {
        assertThat(WalletRefundRail.of("pi_3UGabA9i7EY14IsE14GDluGT")).isEqualTo(WalletRefundRail.STRIPE);
    }

    @Test
    void pawapayDeposit() {
        UUID id = UUID.randomUUID();
        assertThat(WalletRefundRail.of("pawapay:" + id)).isEqualTo(WalletRefundRail.PAWAPAY);
        assertThat(WalletRefundRail.depositId("pawapay:" + id)).isEqualTo(id);
    }

    @Test
    void depositIdOnStripeRef_throws() {
        assertThatThrownBy(() -> WalletRefundRail.depositId("pi_x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPaymentRef_traiteCommeStripe() {
        assertThat(WalletRefundRail.of(null)).isEqualTo(WalletRefundRail.STRIPE);
    }
}

package com.yadony.api.common.stripe;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StripeUnavailableTest {

    @Test
    void apiConnectionException_isANetworkFailure() {
        assertThat(StripeUnavailable.isNetworkFailure(new ApiConnectionException(
                "IOException during API request to Stripe (https://api.stripe.com): Connection reset by peer",
                new IOException("Connection reset by peer")))).isTrue();
    }

    @Test
    void wrappedApiConnectionException_isANetworkFailure() {
        RuntimeException wrapped = new RuntimeException(new ApiConnectionException("reset"));

        assertThat(StripeUnavailable.isNetworkFailure(wrapped)).isTrue();
    }

    @Test
    void stripeRefusalOrNull_isNotANetworkFailure() {
        assertThat(StripeUnavailable.isNetworkFailure(mock(CardException.class))).isFalse();
        assertThat(StripeUnavailable.isNetworkFailure(new IOException("disk"))).isFalse();
        assertThat(StripeUnavailable.isNetworkFailure(null)).isFalse();
    }

    @Test
    void exception_is502StripeUnavailable() {
        YadonyBusinessException e = StripeUnavailable.exception();

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable");
        assertThat(e.getMessage())
                .isEqualTo("Le service de paiement est momentanément injoignable, réessayez.");
    }
}

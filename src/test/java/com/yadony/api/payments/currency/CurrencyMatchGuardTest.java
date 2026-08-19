package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyMatchGuardTest {

    private final CurrencyMatchGuard guard = new CurrencyMatchGuard();

    @Test
    void doesNotThrowWhenCurrenciesMatch() {
        guard.assertMatches("EUR", "EUR");
        guard.assertMatches("eur", "EUR");
        guard.assertMatches("xOf", "XOF");
    }

    @Test
    void throwsCurrencyMismatchWhenDifferent() {
        assertCurrencyMismatch("EUR", "CAD", "EUR", "CAD");
    }

    @Test
    void throwsCurrencyMismatchWhenListingCurrencyIsNull() {
        assertCurrencyMismatch(null, "EUR", "UNKNOWN", "EUR");
    }

    @Test
    void throwsCurrencyMismatchWhenActorCurrencyIsNull() {
        assertCurrencyMismatch("EUR", null, "EUR", "UNKNOWN");
    }

    private void assertCurrencyMismatch(
            String listingCurrency,
            String actorCurrency,
            String expectedListingCurrency,
            String expectedActorCurrency) {
        assertThatThrownBy(() -> guard.assertMatches(listingCurrency, actorCurrency))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException business = (YadonyBusinessException) ex;
                    assertThat(business.getStatus().value()).isEqualTo(422);
                    assertThat(business.getErrorCode()).isEqualTo("currency-mismatch");
                    assertThat(business.getProperties()).containsEntry("listingCurrency", expectedListingCurrency);
                    assertThat(business.getProperties()).containsEntry("actorCurrency", expectedActorCurrency);
                });
    }
}

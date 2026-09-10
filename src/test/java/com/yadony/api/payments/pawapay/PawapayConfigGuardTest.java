package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PawapayConfigGuardTest {

    private static PawapayProperties props(boolean enabled, boolean signatures) {
        return new PawapayProperties(enabled, "https://api.pawapay.io", "tok", signatures, 30,
                "https://api.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void prod_enabled_withoutSignatures_refusesToStart() {
        assertThatThrownBy(() -> new PawapayConfig(props(true, false), "prod").verifyProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAWAPAY_CALLBACK_SIGNATURES");
    }

    @Test
    void prod_enabled_withSignatures_starts() {
        assertThatCode(() -> new PawapayConfig(props(true, true), "prod").verifyProductionSafety())
                .doesNotThrowAnyException();
    }

    @Test
    void prod_disabled_startsWhateverTheSignatureFlag() {
        assertThatCode(() -> new PawapayConfig(props(false, false), "prod").verifyProductionSafety())
                .doesNotThrowAnyException();
    }

    @Test
    void staging_isNotGuarded() {
        assertThatCode(() -> new PawapayConfig(props(true, false), "staging").verifyProductionSafety())
                .doesNotThrowAnyException();
    }

    @Test
    void restClient_isBuiltWithBearerAndBaseUrl() {
        assertThatCode(() -> new PawapayConfig(props(true, true), "dev").pawapayRestClient())
                .doesNotThrowAnyException();
    }
}

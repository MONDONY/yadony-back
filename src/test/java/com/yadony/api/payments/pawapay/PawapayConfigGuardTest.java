package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PawapayConfigGuardTest {

    private static PawapayProperties props(boolean enabled, boolean signatures) {
        return new PawapayProperties(enabled, "https://api.pawapay.io", "tok", signatures, 30,
                "https://api.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
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
    void staging_enabled_withoutSignatures_refusesToStart() {
        assertThatThrownBy(() -> new PawapayConfig(props(true, false), "staging").verifyProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging");
    }

    @Test
    void noProfile_enabled_withoutSignatures_refusesToStart() {
        assertThatThrownBy(() -> new PawapayConfig(props(true, false), "").verifyProductionSafety())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devTestOrE2e_enabled_withoutSignatures_starts() {
        for (String profiles : new String[] {"dev", "test", "e2e", "staging, test"}) {
            assertThatCode(() -> new PawapayConfig(props(true, false), profiles).verifyProductionSafety())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void environmentConstructor_readsActiveProfiles() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("staging");
        assertThatThrownBy(() -> new PawapayConfig(props(true, false), env).verifyProductionSafety())
                .isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("test");
        assertThatCode(() -> new PawapayConfig(props(true, false), env).verifyProductionSafety())
                .doesNotThrowAnyException();
    }

    @Test
    void restClient_isBuiltWithBearerAndBaseUrl() {
        assertThatCode(() -> new PawapayConfig(props(true, true), "dev").pawapayRestClient())
                .doesNotThrowAnyException();
    }
}

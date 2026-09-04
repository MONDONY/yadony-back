package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PawapayCountriesTest {

    @Test
    void roundTrip_forCfaCountries() {
        assertThat(PawapayCountries.toAlpha2("SEN")).isEqualTo("SN");
        assertThat(PawapayCountries.toAlpha2("CIV")).isEqualTo("CI");
        assertThat(PawapayCountries.toAlpha2("CMR")).isEqualTo("CM");
        assertThat(PawapayCountries.toAlpha2("BFA")).isEqualTo("BF");
        assertThat(PawapayCountries.toAlpha3("SN")).isEqualTo("SEN");
        assertThat(PawapayCountries.toAlpha3("CM")).isEqualTo("CMR");
    }

    @Test
    void unknown_returnsNull() {
        assertThat(PawapayCountries.toAlpha2("XXX")).isNull();
        assertThat(PawapayCountries.toAlpha2(null)).isNull();
        assertThat(PawapayCountries.toAlpha3("ZZ")).isNull();
    }
}

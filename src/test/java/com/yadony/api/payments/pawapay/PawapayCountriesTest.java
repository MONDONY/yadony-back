package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PawapayCountriesTest {

    @Test
    void alpha3ToAlpha2_forCfaCountries() {
        assertThat(PawapayCountries.toAlpha2("SEN")).isEqualTo("SN");
        assertThat(PawapayCountries.toAlpha2("CIV")).isEqualTo("CI");
        assertThat(PawapayCountries.toAlpha2("CMR")).isEqualTo("CM");
        assertThat(PawapayCountries.toAlpha2("BFA")).isEqualTo("BF");
        assertThat(PawapayCountries.toAlpha2(" sen ")).as("insensible à la casse et aux espaces").isEqualTo("SN");
    }

    @Test
    void unknown_returnsNull() {
        assertThat(PawapayCountries.toAlpha2("XXX")).isNull();
        assertThat(PawapayCountries.toAlpha2(null)).isNull();
    }
}

package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PawapayProvidersTest {

    @Test
    void label_isHumanReadable() {
        assertThat(PawapayProviders.label("ORANGE_SEN")).isEqualTo("Orange Money");
        assertThat(PawapayProviders.label("WAVE_CIV")).isEqualTo("Wave");
        assertThat(PawapayProviders.label("MTN_MOMO_CMR")).isEqualTo("MTN MoMo");
        assertThat(PawapayProviders.label("FREE_SEN")).isEqualTo("Free Money");
        assertThat(PawapayProviders.label("MOOV_BFA")).isEqualTo("Moov Money");
        assertThat(PawapayProviders.label("AIRTEL_COD")).isEqualTo("Airtel Money");
        assertThat(PawapayProviders.label("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
        assertThat(PawapayProviders.label(null)).isEqualTo("Mobile money");
    }
}

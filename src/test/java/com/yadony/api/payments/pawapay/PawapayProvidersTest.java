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

    @Test
    void brand_isThePrefixBeforeTheFirstUnderscore() {
        assertThat(PawapayProviders.brand("ORANGE_SEN")).isEqualTo("ORANGE");
        assertThat(PawapayProviders.brand("orange_civ")).isEqualTo("ORANGE");
        assertThat(PawapayProviders.brand("MTN_MOMO_CMR")).isEqualTo("MTN");
        assertThat(PawapayProviders.brand("WAVE")).isEqualTo("WAVE");
        assertThat(PawapayProviders.brand(" wave_sen ")).isEqualTo("WAVE");
        assertThat(PawapayProviders.brand(null)).isNull();
        assertThat(PawapayProviders.brand("   ")).isNull();
    }

    /** Revue finale, point 3 (Minor 2) : validé AVANT tout écho d'un code fourni par le client. */
    @Test
    void isWellFormed_validatesClientSuppliedCodes_beforeAnyEcho() {
        assertThat(PawapayProviders.isWellFormed("ORANGE_SEN")).isTrue();
        assertThat(PawapayProviders.isWellFormed("wave_civ")).as("la minuscule n'est pas normalisée par ce helper").isFalse();
        assertThat(PawapayProviders.isWellFormed("_")).isFalse();
        assertThat(PawapayProviders.isWellFormed("A\nB")).isFalse();
        assertThat(PawapayProviders.isWellFormed(null)).isFalse();
    }
}

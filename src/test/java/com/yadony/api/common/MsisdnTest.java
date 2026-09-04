package com.yadony.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MsisdnTest {

    @Test
    void normalize_stripsPlusSpacesAndDashes() {
        assertThat(Msisdn.normalize("+221 77 123-45-67")).isEqualTo("221771234567");
        assertThat(Msisdn.normalize("00221771234567")).isEqualTo("221771234567");
        assertThat(Msisdn.normalize("221771234567")).isEqualTo("221771234567");
    }

    @Test
    void normalize_rejectsGarbage() {
        assertThatThrownBy(() -> Msisdn.normalize("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Msisdn.normalize("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Msisdn.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Msisdn.normalize("12345")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mask_keepsPrefixAndLastTwoDigits() {
        assertThat(Msisdn.mask("221771234567")).isEqualTo("+221 •••• 67");
        assertThat(Msisdn.mask("237690000012")).isEqualTo("+237 •••• 12");
    }
}

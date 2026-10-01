package com.yadony.api.matching.reception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReceptionPhonesTest {

    @ParameterizedTest
    @CsvSource({
            "'+221 77 123 45 67', +221771234567",
            "'00221-77-123-45-67', +221771234567",
            "'+33 (0)6.12.34.56.78', +330612345678",
            "'+225 07 08 09 10 11', +2250708091011",
            "'+12345678', +12345678",
            "'+123456789012345', +123456789012345"
    })
    void toE164_normalizesSeparatorsAndDoubleZero(String raw, String expected) {
        assertThat(ReceptionPhones.toE164(raw)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"77 123 45 67", "0612345678", "+1234567", "+1234567890123456", "+221 77 abc 45 67", "+"})
    void toE164_rejectsNonInternationalOrMalformed(String raw) {
        assertThat(ReceptionPhones.toE164(raw)).isEmpty();
    }

    @Test
    void digitsKey_keepsDigitsAndDropsLeadingDoubleZero() {
        assertThat(ReceptionPhones.digitsKey("+221 77 123 45 67")).isEqualTo("221771234567");
        assertThat(ReceptionPhones.digitsKey("00221 77-123-45-67")).isEqualTo("221771234567");
        assertThat(ReceptionPhones.digitsKey("77 123 45 67")).isEqualTo("771234567");
        assertThat(ReceptionPhones.digitsKey(null)).isEmpty();
        assertThat(ReceptionPhones.digitsKey("abc")).isEmpty();
    }
}

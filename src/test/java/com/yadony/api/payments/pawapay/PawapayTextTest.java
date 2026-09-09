package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PawapayTextTest {

    @Test
    void clamp_truncatesToMaxLength_andKeepsNull() {
        assertThat(PawapayText.clamp(null)).isNull();
        assertThat(PawapayText.clamp("abc")).isEqualTo("abc");
        assertThat(PawapayText.clamp("x".repeat(100))).hasSize(PawapayText.MAX_LENGTH);
    }

    @Test
    void forLog_flattensControlCharacters_thenTruncates() {
        assertThat(PawapayText.forLog(null, 10)).isNull();
        assertThat(PawapayText.forLog("sig-pp=(\"@method\")\r\n[FAKE] entrée forgée\t;keyid=\"K\"", 400))
                .isEqualTo("sig-pp=(\"@method\")  [FAKE] entrée forgée ;keyid=\"K\"");
        assertThat(PawapayText.forLog("a".repeat(50) + "\n" + "b".repeat(50), 60))
                .hasSize(60)
                .doesNotContain("\n");
    }
}

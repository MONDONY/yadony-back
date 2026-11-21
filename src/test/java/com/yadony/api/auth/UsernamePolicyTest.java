package com.yadony.api.auth;

import com.yadony.api.auth.UsernamePolicy.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UsernamePolicyTest {

    @Test
    void normalize_trimEtMinuscules() {
        assertThat(UsernamePolicy.normalize("  Awa.Diallo ")).isEqualTo("awa.diallo");
    }

    @Test
    void normalize_nullDevientVide() {
        assertThat(UsernamePolicy.normalize(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstu", "awa diallo", "awa-d", "é", ".awa", "awa.", "a..b", "_awa", ""})
    void violation_formatInvalide(String value) {
        assertThat(UsernamePolicy.violation(value)).contains(Violation.INVALID_FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"awa.diallo", "awa_d2", "user1785012345"})
    void violation_valides(String value) {
        assertThat(UsernamePolicy.violation(value)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "yadony", "support", "moderation", "officiel", "yadony.support"})
    void violation_reserves(String value) {
        assertThat(UsernamePolicy.violation(value)).contains(Violation.RESERVED);
    }
}

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
    @ValueSource(strings = {"0612345678", "221771234512", "awa.1234567", "a1234567"})
    void violation_ressembleAUnTelephone(String value) {
        assertThat(UsernamePolicy.violation(value)).contains(Violation.LOOKS_LIKE_PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user1785012345", "user12", "awa123456", "awa.12.34567"})
    void violation_chiffresPermis(String value) {
        assertThat(UsernamePolicy.violation(value)).isEqualTo(Optional.empty());
    }

    @Test
    void isWellFormed_neRegardeQueLeFormat() {
        assertThat(UsernamePolicy.isWellFormed("support.awa")).isTrue();
        assertThat(UsernamePolicy.isWellFormed("0612345678")).isTrue();
        assertThat(UsernamePolicy.isWellFormed("a b")).isFalse();
        assertThat(UsernamePolicy.isWellFormed(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "yadony", "support", "moderation", "officiel", "yadony.support",
            "moderateur", "moderator", "official", "staff.awa", "equipe.dony", "yad0ny", "supp0rt", "adm1n",
            "m0d3ration", "0fficiel"})
    void violation_reserves(String value) {
        assertThat(UsernamePolicy.violation(value)).contains(Violation.RESERVED);
    }
}

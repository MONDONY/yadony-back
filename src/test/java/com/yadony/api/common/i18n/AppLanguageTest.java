package com.yadony.api.common.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AppLanguageTest {

    @ParameterizedTest
    @CsvSource({"fr,FR", "EN,EN", "en,EN"})
    void fromCode_reconnaitLeCodeCasseEtBlancsIgnores(String code, AppLanguage expected) {
        assertThat(AppLanguage.fromCode(code)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", ""})
    void fromCode_videPourCodeInconnuOuVide(String code) {
        assertThat(AppLanguage.fromCode(code)).isEmpty();
    }

    @Test
    void fromCode_videPourNull() {
        assertThat(AppLanguage.fromCode(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "en,EN",
            "'en-US,en;q=0.9',EN",
            "fr-FR,FR",
            "de,FR",
            "'de,en;q=0.5',EN",
            "'en;q=0,fr',FR",
            "'fr;q=0.4,en;q=0.8',EN",
            "*,FR",
            "';;;q=x',FR",
    })
    void fromAcceptLanguage_choisitLaPremiereLanguePriseEnCharge(String header, AppLanguage expected) {
        assertThat(AppLanguage.fromAcceptLanguage(header)).isEqualTo(expected);
    }

    @Test
    void fromAcceptLanguage_frSiNull() {
        assertThat(AppLanguage.fromAcceptLanguage(null)).isEqualTo(AppLanguage.FR);
    }

    @Test
    void fromAcceptLanguage_frSiVide() {
        assertThat(AppLanguage.fromAcceptLanguage("")).isEqualTo(AppLanguage.FR);
    }

    @Test
    void isSingular_francaisSingulierJusquA1() {
        assertThat(AppLanguage.FR.isSingular(0)).isTrue();
        assertThat(AppLanguage.FR.isSingular(1)).isTrue();
        assertThat(AppLanguage.FR.isSingular(2)).isFalse();
    }

    @Test
    void isSingular_anglaisSingulierSeulementA1() {
        assertThat(AppLanguage.EN.isSingular(1)).isTrue();
        assertThat(AppLanguage.EN.isSingular(0)).isFalse();
        assertThat(AppLanguage.EN.isSingular(2)).isFalse();
    }
}

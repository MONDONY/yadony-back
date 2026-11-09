package com.yadony.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Forme texte des listes d'indicatifs (réglages de type CALLING_CODES). */
class CallingCodeListTest {

    @Test
    void normalize_retireLesPlusEspacesEtDoublons_dansLOrdreDeSaisie() {
        assertThat(CallingCodeList.normalize(" +225, 221 ,225,,+ 2 3 7 ,"))
                .isEqualTo("225,221,237");
    }

    @Test
    void normalize_videOuBlanc_listeVide() {
        assertThat(CallingCodeList.normalize("")).isEmpty();
        assertThat(CallingCodeList.normalize("   ")).isEmpty();
        assertThat(CallingCodeList.normalize(null)).isEmpty();
        assertThat(CallingCodeList.normalize(" , ,")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22a", "12345", "0225", "221;225", "-1"})
    void normalize_entreeInvalide_refusee(String raw) {
        assertThatThrownBy(() -> CallingCodeList.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indicatif invalide");
    }

    @Test
    void normalize_auPlus60Entrees() {
        String sixty = IntStream.rangeClosed(1, 60).mapToObj(String::valueOf).collect(Collectors.joining(","));
        assertThat(CallingCodeList.normalize(sixty).split(",")).hasSize(60);

        String sixtyOne = sixty + ",61";
        assertThatThrownBy(() -> CallingCodeList.normalize(sixtyOne))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60");
    }

    @Test
    void parseLenient_ignoreLesEntreesIllisibles() {
        assertThat(CallingCodeList.parseLenient("225, x, +221,225,99999")).containsExactly("225", "221");
        assertThat(CallingCodeList.parseLenient("")).isEmpty();
        assertThat(CallingCodeList.parseLenient(null)).isEmpty();
    }

    @Test
    void match_prendLIndicatifLePlusLong() {
        List<String> codes = List.of("1", "1876", "225");
        assertThat(CallingCodeList.match(codes, "+18765550100")).contains("1876");
        assertThat(CallingCodeList.match(codes, "+14155550100")).contains("1");
        assertThat(CallingCodeList.match(codes, "+2250701020304")).contains("225");
    }

    @Test
    void match_aucunIndicatifOuNumeroHorsE164_vide() {
        List<String> codes = List.of("225");
        assertThat(CallingCodeList.match(codes, "+33612345678")).isEmpty();
        assertThat(CallingCodeList.match(codes, "2250701020304")).isEmpty();
        assertThat(CallingCodeList.match(codes, null)).isEmpty();
        assertThat(CallingCodeList.match(List.of(), "+2250701020304")).isEmpty();
    }
}

package com.yadony.api.assistant.formdraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DraftFieldsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-17");

    @Test
    void texteBorneEtSansControle() {
        assertThat(DraftFields.text("  Paris ", 2, 80)).isEqualTo("Paris");
        assertThat(DraftFields.text("P", 2, 80)).isNull();
        assertThat(DraftFields.text("Pa\u0007ris", 2, 80)).isNull();
        assertThat(DraftFields.text(42, 2, 80)).isNull();
    }

    @Test
    void codePays() {
        assertThat(DraftFields.country("ci")).isEqualTo("CI");
        assertThat(DraftFields.country("CIV")).isNull();
    }

    @Test
    void dateDansLaFenetre() {
        assertThat(DraftFields.date("2026-11-15", TODAY, 365)).isEqualTo("2026-11-15");
        assertThat(DraftFields.date("2026-10-17", TODAY, 365)).isEqualTo("2026-10-17");
        assertThat(DraftFields.date("2026-10-16", TODAY, 365)).isNull();
        assertThat(DraftFields.date("2027-10-18", TODAY, 365)).isNull();
        assertThat(DraftFields.date("15/11/2026", TODAY, 365)).isNull();
    }

    @Test
    void heure() {
        assertThat(DraftFields.time("15:00")).isEqualTo("15:00");
        assertThat(DraftFields.time("9:05")).isEqualTo("09:05");
        assertThat(DraftFields.time("24:00")).isNull();
        assertThat(DraftFields.time("15h")).isNull();
    }

    @Test
    void decimalAccepteTexteNumerique() {
        assertThat(DraftFields.decimal("23", BigDecimal.ONE, new BigDecimal("200"))).isEqualByComparingTo("23");
        assertThat(DraftFields.decimal("10,5", BigDecimal.ZERO, new BigDecimal("200"))).isEqualByComparingTo("10.5");
        assertThat(DraftFields.decimal("10 €", BigDecimal.ZERO, new BigDecimal("200"))).isNull();
        assertThat(DraftFields.decimal(0.4, new BigDecimal("0.5"), new BigDecimal("32"))).isNull();
        assertThat(DraftFields.decimal(Double.NaN, BigDecimal.ZERO, BigDecimal.TEN)).isNull();
    }

    @Test
    void decimalSansNotationScientifique() throws Exception {
        BigDecimal ten = DraftFields.decimal(10.0, BigDecimal.ZERO, new BigDecimal("200"));
        assertThat(new ObjectMapper().writeValueAsString(ten)).isEqualTo("10");
        BigDecimal half = DraftFields.decimal("0.50", BigDecimal.ZERO, BigDecimal.TEN);
        assertThat(new ObjectMapper().writeValueAsString(half)).isEqualTo("0.5");
    }

    @Test
    void entier() {
        assertThat(DraftFields.integer(3, 0, 7)).isEqualTo(3);
        assertThat(DraftFields.integer(3.0, 0, 7)).isEqualTo(3);
        assertThat(DraftFields.integer(3.5, 0, 7)).isNull();
        assertThat(DraftFields.integer(8, 0, 7)).isNull();
    }

    @Test
    void booleensEnumsEtDevises() {
        assertThat(DraftFields.bool(true)).isTrue();
        assertThat(DraftFields.bool("true")).isNull();
        assertThat(DraftFields.enumName("plane", TransportMode.class)).isEqualTo("PLANE");
        assertThat(DraftFields.enumName("ROCKET", TransportMode.class)).isNull();
        assertThat(DraftFields.oneOf("mixed", Set.of("KG", "MIXED"))).isEqualTo("MIXED");
        assertThat(DraftFields.currency("eur")).isEqualTo("EUR");
        assertThat(DraftFields.currency("BTC")).isNull();
    }

    @Test
    void listes() {
        assertThat(DraftFields.texts(List.of("Vêtements", " Chaussures ", "x"), 5, 2, 40))
                .containsExactly("Vêtements", "Chaussures");
        assertThat(DraftFields.texts(List.of(), 5, 2, 40)).isNull();
        assertThat(DraftFields.texts("Vêtements", 5, 2, 40)).isNull();
        assertThat(DraftFields.subset(List.of("stripe", "CASH", "CASH", "WAVE"), Set.of("STRIPE", "CASH", "MOBILE_MONEY")))
                .containsExactly("STRIPE", "CASH");
        assertThat(DraftFields.subset(List.of("WAVE"), Set.of("STRIPE"))).isNull();
    }
}

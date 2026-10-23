package com.yadony.api.admin;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSearchTermsTest {

    @Test
    void uuid_reconnuSeulementSousFormeCanonique() {
        UUID id = UUID.randomUUID();
        assertThat(AdminSearchTerms.uuid(" " + id + " ")).isEqualTo(id);
        assertThat(AdminSearchTerms.uuid("DON-1234")).isNull();
        assertThat(AdminSearchTerms.uuid("x".repeat(36))).isNull();
        assertThat(AdminSearchTerms.uuid(null)).isNull();
    }

    @Test
    void like_minusculesSansJokers_ouNulSiVide() {
        assertThat(AdminSearchTerms.like("  Awa_D%iop ")).isEqualTo("%awadiop%");
        assertThat(AdminSearchTerms.like(" %_ ")).isNull();
        assertThat(AdminSearchTerms.like(null)).isNull();
    }

    @Test
    void fold_lowercasesAndRemovesFrenchAccents() {
        assertThat(AdminSearchTerms.fold("ÀÂÄ Çé")).isEqualTo("aaa ce");
        assertThat(AdminSearchTerms.fold("Aïcha")).isEqualTo("aicha");
        // œ devient une seule lettre : les tables SQL TRANSLATE ne savent faire que lettre pour lettre
        assertThat(AdminSearchTerms.fold("Cœur")).isEqualTo("cour");
    }

    @Test
    void fold_collapsesSpaces() {
        assertThat(AdminSearchTerms.fold("  Awa   Diop  ")).isEqualTo("awa diop");
    }

    @Test
    void fold_null_returnsNull() {
        assertThat(AdminSearchTerms.fold(null)).isNull();
    }

    @Test
    void tokens_splitsOnSpacesAndStripsPatternChars() {
        assertThat(AdminSearchTerms.tokens("Paris  Dakar", 4)).containsExactly("paris", "dakar");
        assertThat(AdminSearchTerms.tokens("100%_x", 4)).containsExactly("100x");
    }

    @Test
    void tokens_limitsToMax() {
        assertThat(AdminSearchTerms.tokens("a b c d e f", 4)).containsExactly("a", "b", "c", "d");
    }

    @Test
    void tokens_blank_returnsEmpty() {
        assertThat(AdminSearchTerms.tokens(null, 4)).isEmpty();
        assertThat(AdminSearchTerms.tokens("   ", 4)).isEmpty();
        assertThat(AdminSearchTerms.tokens(" %_ ", 4)).isEmpty();
    }

    @Test
    void likeFolded_wrapsWithPercent() {
        assertThat(AdminSearchTerms.likeFolded("dakar")).isEqualTo("%dakar%");
    }

    @Test
    void digitsOnly_keepsDigits() {
        assertThat(AdminSearchTerms.digitsOnly("+33 6 12-34")).isEqualTo("3361234");
        assertThat(AdminSearchTerms.digitsOnly("12")).isNull();
        assertThat(AdminSearchTerms.digitsOnly(null)).isNull();
    }
}

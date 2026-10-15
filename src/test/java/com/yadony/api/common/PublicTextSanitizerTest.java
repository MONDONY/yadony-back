package com.yadony.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicTextSanitizerTest {

    @Test
    void numerosMasques() {
        assertThat(PublicTextSanitizer.maskContacts("Moussa 77 123 45 67"))
                .isEqualTo("Moussa " + PublicTextSanitizer.MASK);
        assertThat(PublicTextSanitizer.maskContacts("tel +33 6 12 34 56 78."))
                .isEqualTo("tel " + PublicTextSanitizer.MASK + ".");
        assertThat(PublicTextSanitizer.maskContacts("appeler le 0612345678"))
                .isEqualTo("appeler le " + PublicTextSanitizer.MASK);
        assertThat(PublicTextSanitizer.maskContacts("(221) 77-123-45-67"))
                .doesNotContain("123");
    }

    // Revue sécu : séparateurs et chiffres non ASCII ne doivent pas faire passer un numéro.
    @Test
    void separateursVariesEtChiffresNonAscii_masques() {
        assertThat(PublicTextSanitizer.maskContacts("77/123/45/67")).isEqualTo(PublicTextSanitizer.MASK);
        assertThat(PublicTextSanitizer.maskContacts("77,123,45,67")).isEqualTo(PublicTextSanitizer.MASK);
        assertThat(PublicTextSanitizer.maskContacts("77_123_45_67")).isEqualTo(PublicTextSanitizer.MASK);
        assertThat(PublicTextSanitizer.maskContacts("７７１２３４５６７")).isEqualTo(PublicTextSanitizer.MASK);
        assertThat(PublicTextSanitizer.maskContacts("٧٧١٢٣٤٥٦٧")).isEqualTo(PublicTextSanitizer.MASK);
    }

    @Test
    void emailAvecEspacesAutourDeLArobase_masque() {
        assertThat(PublicTextSanitizer.maskContacts("moussa @ mail.com"))
                .doesNotContain("moussa").doesNotContain("mail.com");
    }

    @Test
    void emailMasque() {
        assertThat(PublicTextSanitizer.maskContacts("écrire à moussa.k+1@mail.sn svp"))
                .isEqualTo("écrire à " + PublicTextSanitizer.MASK + " svp");
    }

    @Test
    void petitsNombresConserves() {
        assertThat(PublicTextSanitizer.maskContacts("2 boubous, 1 kg de café, lot 2024, 12.5 kg"))
                .isEqualTo("2 boubous, 1 kg de café, lot 2024, 12.5 kg");
    }

    @Test
    void nulOuVide() {
        assertThat(PublicTextSanitizer.maskContacts(null)).isEmpty();
        assertThat(PublicTextSanitizer.maskContacts("  ")).isEqualTo("  ");
    }
}

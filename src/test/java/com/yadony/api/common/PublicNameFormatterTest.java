package com.yadony.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicNameFormatterTest {

    @Test
    void prenomEtNom_donnePrenomEtInitiale() {
        assertThat(PublicNameFormatter.shortName("Fatou Diop")).isEqualTo("Fatou D.");
    }

    @Test
    void nomCompose_garderPremierMotEtInitialeDuDernier() {
        assertThat(PublicNameFormatter.shortName("Mamadou Lamine Diop")).isEqualTo("Mamadou D.");
    }

    @Test
    void espacesMultiplesEtBords_ignores() {
        assertThat(PublicNameFormatter.shortName("  Awa    ndiaye ")).isEqualTo("Awa N.");
    }

    @Test
    void initialeAccentuee_conservee() {
        assertThat(PublicNameFormatter.shortName("Éric Élégbédé")).isEqualTo("Éric É.");
    }

    @Test
    void unSeulMot_renduTelQuel() {
        assertThat(PublicNameFormatter.shortName("Moussa")).isEqualTo("Moussa");
    }

    @Test
    void nulOuVide_donneNonPrecise() {
        assertThat(PublicNameFormatter.shortName(null)).isEqualTo(PublicNameFormatter.UNKNOWN);
        assertThat(PublicNameFormatter.shortName("   ")).isEqualTo(PublicNameFormatter.UNKNOWN);
    }
}

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

    // Le nom du destinataire est saisi librement par l'expéditeur : un numéro ou un
    // e-mail tapé dedans ne doit jamais sortir sur la page publique, même en partie.
    @Test
    void numeroSeul_jamaisAffiche() {
        assertThat(PublicNameFormatter.shortName("+221770000000")).isEqualTo(PublicNameFormatter.UNKNOWN);
        assertThat(PublicNameFormatter.shortName("77 000 00 00")).isEqualTo(PublicNameFormatter.UNKNOWN);
    }

    @Test
    void numeroOuEmailAvecLeNom_retires() {
        assertThat(PublicNameFormatter.shortName("Moussa Keita 770000000")).isEqualTo("Moussa K.");
        assertThat(PublicNameFormatter.shortName("+221770000000 Moussa")).isEqualTo("Moussa");
        assertThat(PublicNameFormatter.shortName("Moussa moussa@mail.com")).isEqualTo("Moussa");
        assertThat(PublicNameFormatter.shortName("Moussa77 Keita")).isEqualTo("Keita");
    }

    @Test
    void premierMotTropLong_tronque() {
        assertThat(PublicNameFormatter.shortName("a".repeat(80))).hasSize(PublicNameFormatter.MAX_FIRST_NAME_LENGTH);
    }

    @Test
    void nulOuVide_donneNonPrecise() {
        assertThat(PublicNameFormatter.shortName(null)).isEqualTo(PublicNameFormatter.UNKNOWN);
        assertThat(PublicNameFormatter.shortName("   ")).isEqualTo(PublicNameFormatter.UNKNOWN);
    }
}

package com.yadony.api.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Liste blanche du texte riche de l'assistant (YADONY-FLUTTER-PROD-13). */
class AssistantRichTextTest {

    @Test
    void gardeGrasPucesEtLignes() {
        String rich = "Payez depuis **Mon argent**.\n- Ouvrez **Portefeuille**\n- Touchez **Recharger**";
        assertThat(AssistantRichText.sanitize(rich)).isEqualTo(rich);
    }

    @Test
    void titresAplatisLignesVidesRetirees() {
        assertThat(AssistantRichText.sanitize("## Étapes\n\n\nOuvrez l'écran.\r\n### Fin"))
                .isEqualTo("Étapes\nOuvrez l'écran.\nFin");
    }

    @Test
    void liensRemplacesParLeurTexte() {
        assertThat(AssistantRichText.sanitize("Voir [la FAQ](https://yadony.com/faq) ou [ici](javascript:x)."))
                .isEqualTo("Voir la FAQ ou ici.");
    }

    @Test
    void tableauxCodeEtItaliquesRetires() {
        assertThat(AssistantRichText.sanitize("| Moyen | Délai |\n|---|---|\n| Carte | `2 j` |\n*vite* et _bien_"))
                .isEqualTo("Moyen Délai\nCarte 2 j\nvite et _bien_");
    }

    @Test
    void puceEtoileNumeroteeEtImbriqueeDeviennentDesPucesSimples() {
        assertThat(AssistantRichText.sanitize("* un\n  - deux\n1. trois\n2) quatre\n• cinq"))
                .isEqualTo("- un\n- deux\n- trois\n- quatre\n- cinq");
    }

    @Test
    void ligneQuiCommenceParDuGrasNEstPasUnePuce() {
        assertThat(AssistantRichText.sanitize("**12 %** de commission")).isEqualTo("**12 %** de commission");
    }

    @Test
    void grasOrphelinOuVideRetire() {
        assertThat(AssistantRichText.sanitize("Délai de **48 h pour confirmer")).isEqualTo("Délai de 48 h pour confirmer");
        assertThat(AssistantRichText.sanitize("A **** B ** C")).isEqualTo("A B C");
        assertThat(AssistantRichText.sanitize("**")).isEmpty();
    }

    @Test
    void auPlusTroisGras() {
        assertThat(AssistantRichText.sanitize("**a** **b**\n- **c** **d** **e**"))
                .isEqualTo("**a** **b**\n- **c** d e");
    }

    @Test
    void nullOuVide() {
        assertThat(AssistantRichText.sanitize(null)).isEmpty();
        assertThat(AssistantRichText.sanitize(" \n \n")).isEmpty();
    }

    @Test
    void versionBruteSansBalise() {
        assertThat(AssistantRichText.plain("Allez dans **Moi**.\n- Touchez **Profil**\n- Puis Valider"))
                .isEqualTo("Allez dans Moi.\n• Touchez Profil\n• Puis Valider");
        assertThat(AssistantRichText.plain(null)).isEmpty();
    }

    @Test
    void troncatureSurFinDeLigne() {
        String rich = "Phrase de tête assez longue pour compter.\n- Étape une\n- Étape deux qui déborde largement";
        String cut = AssistantRichText.truncate(rich, 70);
        assertThat(cut).isEqualTo("Phrase de tête assez longue pour compter.\n- Étape une");
    }

    @Test
    void troncatureSurFinDeMotAvecPointsDeSuspension() {
        String cut = AssistantRichText.truncate("Un deux trois quatre cinq six sept", 20);
        assertThat(cut).isEqualTo("Un deux trois…").hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void troncatureAuMilieuDUnGrasLeReferme() {
        String cut = AssistantRichText.truncate("Le délai est de **quarante huit heures ouvrées** après", 32);
        assertThat(cut).isEqualTo("Le délai est de **quarante**…");
        assertThat(cut.split("\\*\\*", -1)).hasSize(3);
    }

    @Test
    void troncatureJusteApresUneOuvertureRetireLEtoile() {
        String cut = AssistantRichText.truncate("Ouvrez maintenant **Portefeuille** puis", 21);
        assertThat(cut).doesNotContain("**").endsWith("…");
    }

    @Test
    void texteCourtIntact() {
        assertThat(AssistantRichText.truncate("court", 10)).isEqualTo("court");
    }

    @Test
    void troncatureNeCoupePasUnEmoji() {
        String text = "a".repeat(8) + "😀" + "b";
        assertThat(AssistantRichText.truncate(text, 10)).doesNotContain("\uD83D").endsWith("…");
    }
}

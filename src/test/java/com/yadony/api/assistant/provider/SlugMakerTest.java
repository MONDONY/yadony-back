package com.yadony.api.assistant.provider;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlugMakerTest {

    @Test
    void slugify() {
        assertThat(SlugMaker.slugify("Groq")).isEqualTo("groq");
        assertThat(SlugMaker.slugify("  Gemini Flash (gratuit) ")).isEqualTo("gemini-flash-gratuit");
        assertThat(SlugMaker.slugify("Modèle Été")).isEqualTo("modele-ete");
        assertThat(SlugMaker.slugify("!!!")).isEqualTo("fournisseur");
        assertThat(SlugMaker.slugify("x".repeat(80))).hasSize(40);
    }

    @Test
    void uniqueAjouteUnSuffixe() {
        Set<String> taken = Set.of("groq", "groq-2");
        assertThat(SlugMaker.unique("Groq", taken::contains)).isEqualTo("groq-3");
        assertThat(SlugMaker.unique("Mistral", taken::contains)).isEqualTo("mistral");
    }
}

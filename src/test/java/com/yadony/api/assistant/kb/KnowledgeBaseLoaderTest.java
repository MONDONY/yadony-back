package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseLoaderTest {

    private static final String VALID = """
            ---
            id: changer-numero
            title: Changer mon numéro de téléphone
            keywords: numéro, téléphone, SMS
            routes:
              - path: /profile/edit/phone
                label: Changer mon numéro
            ---
            Le numéro se change depuis le profil : Moi › Modifier le profil › Téléphone.
            """;

    @Test
    void parse_litLEnTeteEtLeCorps() {
        KbArticle a = KnowledgeBaseLoader.parse("fr", "changer-numero.md", VALID);
        assertThat(a.id()).isEqualTo("changer-numero");
        assertThat(a.title()).isEqualTo("Changer mon numéro de téléphone");
        assertThat(a.keywords()).containsExactly("numéro", "téléphone", "SMS");
        assertThat(a.routes()).containsExactly(new KbRoute("/profile/edit/phone", "Changer mon numéro"));
        assertThat(a.body()).startsWith("Le numéro se change");
    }

    @Test
    void parse_sansRoutesNiMotsCles() {
        String raw = "---\nid: general\ntitle: Yadony\n---\nTexte.\n";
        KbArticle a = KnowledgeBaseLoader.parse("fr", "general.md", raw);
        assertThat(a.routes()).isEmpty();
        assertThat(a.keywords()).isEmpty();
    }

    @Test
    void parse_refuseUneFicheSansEnTete() {
        assertThatThrownBy(() -> KnowledgeBaseLoader.parse("fr", "x.md", "Texte seul"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fr/x.md");
    }

    @Test
    void validate_refuseUneRouteAvecParametre() {
        String raw = VALID.replace("/profile/edit/phone", "/negotiations/:id");
        assertInvalid(raw, "route");
    }

    @Test
    void validate_refuseUneRouteAvecRequete() {
        assertInvalid(VALID.replace("/profile/edit/phone", "/profile?x=1"), "route");
    }

    @Test
    void validate_refuseUnLibelleTropLong() {
        assertInvalid(VALID.replace("label: Changer mon numéro", "label: " + "x".repeat(41)), "label");
    }

    @Test
    void validate_refuseLeTiretCadratin() {
        assertInvalid(VALID.replace("Le numéro se change", "Le numéro — se change"), "tiret cadratin");
    }

    @Test
    void validate_refuseUnIdInvalide() {
        assertInvalid(VALID.replace("id: changer-numero", "id: Changer Numéro"), "id");
    }

    @Test
    void validate_refuseUnIdEnDouble() {
        KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md", VALID);
        KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
        assertThatThrownBy(() -> KnowledgeBaseLoader.validate(Map.of("fr", List.of(a, a), "en", List.of(en))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("en double");
    }

    @Test
    void validate_refuseUneFicheFrancaiseSansTraduction() {
        KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md", VALID);
        assertThatThrownBy(() -> KnowledgeBaseLoader.validate(Map.of("fr", List.of(a), "en", List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("changer-numero");
    }

    @Test
    void validate_accepteUnCorpusCoherent() {
        KbArticle fr = KnowledgeBaseLoader.parse("fr", "a.md", VALID);
        KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
        KnowledgeBaseLoader.validate(Map.of("fr", List.of(fr), "en", List.of(en)));
    }

    private static void assertInvalid(String raw, String fragment) {
        assertThatThrownBy(() -> {
            KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md", raw);
            KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
            KnowledgeBaseLoader.validate(Map.of("fr", List.of(a), "en", List.of(en)));
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining(fragment);
    }
}

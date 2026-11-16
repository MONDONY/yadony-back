package com.yadony.api.assistant.adminhelp;

import com.yadony.api.assistant.kb.KbRoute;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminHelpLoaderTest {

    static final String OK = """
            ---
            id: voir-colis
            title: Voir les colis
            category: activite
            order: 2
            summary: Le résumé.
            permission: BID_VIEW
            keywords: colis, en cours
            routes:
              - path: /colis?view=ACTIVE
                label: Ouvrir
            ---
            ## Titre
            Voir [la page](/colis) et [l'autre fiche](/aide/voir-colis).
            """;

    static AdminHelpArticle article(String id, String body, List<KbRoute> routes) {
        return new AdminHelpArticle(id, "Titre " + id, "activite", 1, "Résumé", null, List.of("mot"), routes, body);
    }

    @Test
    void litUneFicheComplete() {
        AdminHelpArticle a = AdminHelpLoader.parse("voir-colis.md", OK);
        assertThat(a.id()).isEqualTo("voir-colis");
        assertThat(a.order()).isEqualTo(2);
        assertThat(a.permission()).isEqualTo("BID_VIEW");
        assertThat(a.keywords()).containsExactly("colis", "en cours");
        assertThat(a.routes()).containsExactly(new KbRoute("/colis?view=ACTIVE", "Ouvrir"));
        assertThat(a.body()).startsWith("## Titre");
        AdminHelpLoader.validate(List.of(a));
    }

    @Test
    void motsClesEnListeEtOrdreParDefaut() {
        AdminHelpArticle a = AdminHelpLoader.parse(null, "---\nid: abc\ntitle: T\ncategory: comptes\nsummary: S\n"
                + "keywords: [un, deux]\n---\nCorps");
        assertThat(a.keywords()).containsExactly("un", "deux");
        assertThat(a.order()).isEqualTo(100);
        assertThat(a.routes()).isEmpty();
    }

    @Test
    void refuseLesEnTetesMalFormes() {
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "pas d'en-tête")).hasMessageContaining("manquant");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\n")).hasMessageContaining("fin d'en-tête");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\n: : :\n---\nx")).hasMessageContaining("illisible");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\n- liste\n---\nx")).hasMessageContaining("illisible");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\nfoo: 1\n---\nx")).hasMessageContaining("inconnue");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\norder: deux\n---\nx")).hasMessageContaining("order");
        assertThatThrownBy(() -> AdminHelpLoader.parse("y.md", "---\nid: x\n---\nx")).hasMessageContaining("nom du fichier");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\nkeywords: 3\n---\nx")).hasMessageContaining("mots-clés");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\nkeywords: [1]\n---\nx")).hasMessageContaining("mots-clés");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\nroutes: /a\n---\nx")).hasMessageContaining("liste");
        assertThatThrownBy(() -> AdminHelpLoader.parse("x.md", "---\nid: x\nroutes: [a]\n---\nx")).hasMessageContaining("route illisible");
    }

    @Test
    void refuseLesFichesInvalides() {
        List<KbRoute> none = List.of();
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("A", "c", none)))).hasMessageContaining("id invalide");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "c", none), article("abc", "c", none))))
                .hasMessageContaining("double");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(new AdminHelpArticle("abc", " ", "activite", 1, "S", null,
                List.of(), none, "c")))).hasMessageContaining("title");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(new AdminHelpArticle("abc", "T", "autre", 1, "S", null,
                List.of(), none, "c")))).hasMessageContaining("category");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(new AdminHelpArticle("abc", "T", "activite", 1, "", null,
                List.of(), none, "c")))).hasMessageContaining("summary");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "", none)))).hasMessageContaining("corps");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(new AdminHelpArticle("abc", "T", "activite", 1, "S", "NOPE",
                List.of(), none, "c")))).hasMessageContaining("permission inconnue");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "c", List.of(
                new KbRoute("/colis", "a"), new KbRoute("/users", "b"), new KbRoute("/kyc", "c"),
                new KbRoute("/promo", "d"), new KbRoute("/audit", "e")))))).hasMessageContaining("routes");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "c", List.of(new KbRoute("https://x.com", "a"))))))
                .hasMessageContaining("route invalide");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "c", List.of(new KbRoute("/inventee", "a"))))))
                .hasMessageContaining("page inconnue");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "c", List.of(new KbRoute("/colis", " "))))))
                .hasMessageContaining("label");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "a — b", none)))).hasMessageContaining("tiret");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "a <b>", none)))).hasMessageContaining("HTML");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "[x](/aide/absente)", none))))
                .hasMessageContaining("fiche inconnue");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "[x](https://evil.com)", none))))
                .hasMessageContaining("lien invalide");
        assertThatThrownBy(() -> AdminHelpLoader.validate(List.of(article("abc", "[x](/inventee)", none))))
                .hasMessageContaining("lien invalide");
    }

    @Test
    void dossierIntrouvableOuVide() throws Exception {
        assertThatThrownBy(() -> AdminHelpLoader.load(new PathMatchingResourcePatternResolver(), "classpath:introuvable-xyz"))
                .hasMessageContaining("centre d'aide admin");
        org.springframework.core.io.support.ResourcePatternResolver empty =
                org.mockito.Mockito.mock(org.springframework.core.io.support.ResourcePatternResolver.class);
        org.mockito.Mockito.when(empty.getResources(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new org.springframework.core.io.Resource[0]);
        assertThatThrownBy(() -> AdminHelpLoader.load(empty, "x")).hasMessageContaining("introuvables");
    }

    @Test
    void pages() {
        assertThat(AdminHelpPages.known("/colis?view=ACTIVE")).isTrue();
        assertThat(AdminHelpPages.known("/aide")).isTrue();
        assertThat(AdminHelpPages.known("/nope")).isFalse();
        assertThat(AdminHelpPages.allowed("/colis", java.util.Set.of("BID_VIEW"))).isTrue();
        assertThat(AdminHelpPages.allowed("/colis", java.util.Set.of())).isFalse();
        assertThat(AdminHelpPages.allowed("/change-password", java.util.Set.of())).isTrue();
    }
}

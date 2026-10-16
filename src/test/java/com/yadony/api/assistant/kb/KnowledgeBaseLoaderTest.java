package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @TempDir
    Path dir;

    // ---- parse ----

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
        assertParseFails("Texte seul", "Fiche assistant invalide (fr/a.md) : en-tête --- manquant");
    }

    @Test
    void parse_refuseUneFermetureManquante() {
        assertParseFails("---\nid: general\ntitle: Yadony\nTexte\n",
                "Fiche assistant invalide (fr/a.md) : fin d'en-tête --- manquante");
    }

    @Test
    void parse_enTeteVideDonneEnTeteIllisible() {
        assertParseFails("---\n---\nCorps.\n", "Fiche assistant invalide (fr/a.md) : en-tête illisible");
    }

    @Test
    void parse_yamlMalformeDonneEnTeteIllisible() {
        assertParseFails("---\nid: [\ntitle: x\n---\nCorps.\n",
                "Fiche assistant invalide (fr/a.md) : en-tête illisible");
    }

    @Test
    void parse_refuseUneCleDEnTeteInconnue() {
        assertParseFails(VALID.replace("keywords:", "auteur: Moi\nkeywords:"),
                "Fiche assistant invalide (fr/a.md) : clé d'en-tête inconnue : auteur");
    }

    @Test
    void parse_accepteLesMotsClesEnListeYaml() {
        KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md",
                VALID.replace("keywords: numéro, téléphone, SMS", "keywords: [numéro, SMS]"));
        assertThat(a.keywords()).containsExactly("numéro", "SMS");
    }

    @Test
    void parse_refuseDesMotsClesDeMauvaisType() {
        assertParseFails(VALID.replace("keywords: numéro, téléphone, SMS", "keywords: 42"),
                "Fiche assistant invalide (fr/a.md) : mots-clés illisibles");
    }

    @Test
    void parse_refuseDesMotsClesListeAvecUnNonTexte() {
        assertParseFails(VALID.replace("keywords: numéro, téléphone, SMS", "keywords: [numéro, 42]"),
                "Fiche assistant invalide (fr/a.md) : mots-clés illisibles");
    }

    @Test
    void parse_refuseDesRoutesQuiNeSontPasUneListe() {
        assertParseFails("---\nid: general\ntitle: Yadony\nroutes: /profile\n---\nTexte.\n",
                "Fiche assistant invalide (fr/a.md) : routes doit être une liste");
    }

    @Test
    void parse_refuseUneRouteQuiNEstPasUneCarte() {
        assertParseFails("---\nid: general\ntitle: Yadony\nroutes:\n  - /profile\n---\nTexte.\n",
                "Fiche assistant invalide (fr/a.md) : route illisible");
    }

    // ---- validate ----

    @Test
    void validate_refuseUneRouteAvecParametre() {
        assertInvalid(VALID.replace("/profile/edit/phone", "/negotiations/:id"),
                "Fiche assistant invalide (fr/changer-numero) : route invalide : /negotiations/:id");
    }

    @Test
    void validate_refuseUneRouteAvecRequete() {
        assertInvalid(VALID.replace("/profile/edit/phone", "/profile?x=1"),
                "Fiche assistant invalide (fr/changer-numero) : route invalide : /profile?x=1");
    }

    @Test
    void validate_refuseUneRouteSansSlashInitial() {
        assertInvalid(VALID.replace("/profile/edit/phone", "profile/edit/phone"),
                "Fiche assistant invalide (fr/changer-numero) : route invalide : profile/edit/phone");
    }

    @Test
    void validate_refuseUneRouteAvecDiese() {
        assertInvalid(VALID.replace("/profile/edit/phone", "/profile#x"),
                "Fiche assistant invalide (fr/changer-numero) : route invalide : /profile#x");
    }

    @Test
    void validate_refusePlusDeTroisRoutes() {
        String raw = """
                ---
                id: changer-numero
                title: Changer mon numéro
                routes:
                  - path: /a
                    label: A
                  - path: /b
                    label: B
                  - path: /c
                    label: C
                  - path: /d
                    label: D
                ---
                Corps.
                """;
        assertInvalid(raw, "Fiche assistant invalide (fr/changer-numero) : plus de 3 routes");
    }

    @Test
    void validate_refuseUnLibelleTropLong() {
        assertInvalid(VALID.replace("label: Changer mon numéro", "label: " + "x".repeat(41)),
                "Fiche assistant invalide (fr/changer-numero) : label vide ou trop long");
    }

    @Test
    void validate_refuseLeTiretCadratinDansUnLibelle() {
        assertInvalid(VALID.replace("label: Changer mon numéro", "label: Changer — mon numéro"),
                "Fiche assistant invalide (fr/changer-numero) : tiret cadratin dans un label");
    }

    @Test
    void validate_refuseLeTiretCadratinDansLeCorps() {
        assertInvalid(VALID.replace("Le numéro se change", "Le numéro — se change"),
                "Fiche assistant invalide (fr/changer-numero) : tiret cadratin dans le texte");
    }

    @Test
    void validate_refuseLeTiretCadratinDansLeTitre() {
        assertInvalid(VALID.replace("title: Changer mon numéro de téléphone", "title: Changer — mon numéro"),
                "Fiche assistant invalide (fr/changer-numero) : tiret cadratin dans le texte");
    }

    @Test
    void validate_refuseLeTiretCadratinDansUnMotCle() {
        assertInvalid(VALID.replace("keywords: numéro, téléphone, SMS", "keywords: numéro, tél — SMS"),
                "Fiche assistant invalide (fr/changer-numero) : tiret cadratin dans les mots-clés");
    }

    @Test
    void validate_refuseUnIdInvalide() {
        assertInvalid(VALID.replace("id: changer-numero", "id: Changer Numéro"),
                "Fiche assistant invalide (fr/Changer Numéro) : id invalide");
    }

    @Test
    void validate_refuseUnIdEnDouble() {
        KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md", VALID);
        KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
        assertThatThrownBy(() -> KnowledgeBaseLoader.validate(Map.of("fr", List.of(a, a), "en", List.of(en))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fiche assistant invalide (fr/changer-numero) : id en double");
    }

    @Test
    void validate_refuseUnTitreVide() {
        assertInvalid(VALID.replace("title: Changer mon numéro de téléphone", "title: \"  \""),
                "Fiche assistant invalide (fr/changer-numero) : title vide ou trop long");
    }

    @Test
    void validate_refuseUnTitreTropLong() {
        assertInvalid(VALID.replace("title: Changer mon numéro de téléphone", "title: " + "x".repeat(121)),
                "Fiche assistant invalide (fr/changer-numero) : title vide ou trop long");
    }

    @Test
    void validate_refuseUnCorpsVide() {
        assertInvalid("---\nid: changer-numero\ntitle: Changer mon numéro\n---\n",
                "Fiche assistant invalide (fr/changer-numero) : corps vide ou trop long");
    }

    @Test
    void validate_refuseUnCorpsTropLong() {
        assertInvalid("---\nid: changer-numero\ntitle: Changer mon numéro\n---\n" + "x".repeat(2001) + "\n",
                "Fiche assistant invalide (fr/changer-numero) : corps vide ou trop long");
    }

    @Test
    void validate_refuseUneFicheFrancaiseSansTraduction() {
        KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md", VALID);
        assertThatThrownBy(() -> KnowledgeBaseLoader.validate(Map.of("fr", List.of(a), "en", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fiche assistant invalide (en/changer-numero) : traduction manquante de changer-numero");
    }

    @Test
    void validate_refuseUneFicheAnglaiseSansTraduction() {
        KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
        assertThatThrownBy(() -> KnowledgeBaseLoader.validate(Map.of("fr", List.of(), "en", List.of(en))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fiche assistant invalide (fr/changer-numero) : traduction manquante de changer-numero");
    }

    @Test
    void validate_accepteUnCorpusCoherent() {
        KbArticle fr = KnowledgeBaseLoader.parse("fr", "a.md", VALID);
        KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
        KnowledgeBaseLoader.validate(Map.of("fr", List.of(fr), "en", List.of(en)));
    }

    // ---- load ----

    @Test
    void load_chargeLesFichesTriesParNomDeFichier() throws IOException {
        Files.createDirectories(dir.resolve("fr"));
        Files.createDirectories(dir.resolve("en"));
        // Le nom de fichier (z.md avant a.md) n'est pas l'ordre de l'id : le tri se fait sur le nom.
        Files.writeString(dir.resolve("fr/z.md"), md("premier", "Premier"));
        Files.writeString(dir.resolve("fr/a.md"), md("second", "Second"));
        Files.writeString(dir.resolve("en/z.md"), md("premier", "First"));
        Files.writeString(dir.resolve("en/a.md"), md("second", "Second"));

        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), root());

        assertThat(corpus.articles("fr")).extracting(KbArticle::id).containsExactly("second", "premier");
        assertThat(corpus.articles("en")).extracting(KbArticle::id).containsExactly("second", "premier");
    }

    @Test
    void load_refuseUneLocaleSansFiche() throws IOException {
        Files.createDirectories(dir.resolve("fr"));
        Files.createDirectories(dir.resolve("en"));
        Files.writeString(dir.resolve("fr/a.md"), md("premier", "Premier"));

        assertThatThrownBy(() -> KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), root()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fiches assistant introuvables (en) dans " + root());
    }

    @Test
    void load_refuseUnDossierAbsent() {
        assertThatThrownBy(() -> KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), root()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fiches assistant introuvables (fr) dans " + root());
    }

    // ---- helpers ----

    private String root() {
        return "file:" + dir.toAbsolutePath();
    }

    private static String md(String id, String title) {
        return "---\nid: " + id + "\ntitle: " + title + "\n---\nCorps de " + id + ".\n";
    }

    private static void assertParseFails(String raw, String expectedMessage) {
        assertThatThrownBy(() -> KnowledgeBaseLoader.parse("fr", "a.md", raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);
    }

    private static void assertInvalid(String raw, String expectedMessage) {
        assertThatThrownBy(() -> {
            KbArticle a = KnowledgeBaseLoader.parse("fr", "a.md", raw);
            KbArticle en = KnowledgeBaseLoader.parse("en", "a.md", VALID);
            KnowledgeBaseLoader.validate(Map.of("fr", List.of(a), "en", List.of(en)));
        }).isInstanceOf(IllegalStateException.class).hasMessage(expectedMessage);
    }
}

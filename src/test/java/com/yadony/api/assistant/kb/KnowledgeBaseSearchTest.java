package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseSearchTest {

    private static KnowledgeBase kb;

    @BeforeAll
    static void setUp() {
        KbArticle phoneFr = new KbArticle("fr", "changer-numero", "Changer mon numéro de téléphone",
                List.of("numéro", "téléphone", "portable"), List.of(new KbRoute("/profile/edit/phone", "Changer mon numéro")),
                "Le numéro se change depuis le profil. Un code est envoyé par SMS.");
        KbArticle tripFr = new KbArticle("fr", "proposer-trajet", "Proposer un trajet",
                List.of("trajet", "voyage", "kilos"), List.of(new KbRoute("/trips/create", "Proposer un trajet")),
                "Pour proposer un trajet, indiquez vos dates et les kilos disponibles. Vous pouvez annuler un trajet.");
        KbArticle cancelFr = new KbArticle("fr", "annuler-trajet", "Annuler un trajet",
                List.of("annuler", "annulation"), List.of(),
                "Un trajet s'annule depuis sa fiche.");
        KbArticle phoneEn = new KbArticle("en", "changer-numero", "Change my phone number",
                List.of("number", "phone"), List.of(new KbRoute("/profile/edit/phone", "Change my number")),
                "Change your number from your profile. A code is sent by text message.");
        KbArticle tripEn = new KbArticle("en", "proposer-trajet", "Offer a trip", List.of("trip"), List.of(), "Offer a trip.");
        KbArticle cancelEn = new KbArticle("en", "annuler-trajet", "Cancel a trip", List.of("cancel"), List.of(), "Cancel a trip.");
        kb = new KnowledgeBase(new KbCorpus(Map.of(
                "fr", List.of(phoneFr, tripFr, cancelFr), "en", List.of(phoneEn, tripEn, cancelEn))), 0.0);
    }

    @Test
    void trouveLaFicheSansAccents() {
        assertThat(ids(kb.search("fr", "numero de telephone", null, 4))).first().isEqualTo("changer-numero");
    }

    @Test
    void trouveLaFicheAvecUneAutreFormulation() {
        assertThat(ids(kb.search("fr", "comment je modifie mon portable ?", null, 4))).first().isEqualTo("changer-numero");
    }

    @Test
    void ramenePluriels() {
        assertThat(ids(kb.search("fr", "les trajets", null, 4))).contains("proposer-trajet");
    }

    @Test
    void chercheEnAnglais() {
        assertThat(ids(kb.search("en", "how do I change my phone number", null, 4))).first().isEqualTo("changer-numero");
    }

    @Test
    void ignoreLaSyntaxeLucene() {
        assertThat(kb.search("fr", "trajet* AND ~annul OR \"", null, 4)).isNotNull();
    }

    @Test
    void questionVideOuMotsVides() {
        assertThat(kb.search("fr", "?", null, 4)).isEmpty();
        assertThat(kb.search("fr", "le la les de", null, 4)).isEmpty();
    }

    @Test
    void horsSujet() {
        assertThat(kb.search("fr", "recette du poulet yassa", null, 4)).isEmpty();
    }

    @Test
    void relanceCourteUtiliseLaQuestionPrecedente() {
        List<String> found = ids(kb.search("fr", "et pour l'annuler ?", "comment proposer un trajet", 4));
        assertThat(found).contains("annuler-trajet", "proposer-trajet");
    }

    @Test
    void respecteLaLimite() {
        assertThat(kb.search("fr", "trajet numéro annuler", null, 2)).hasSize(2);
    }

    @Test
    void localeInconnueRetombeSurLeFrancais() {
        assertThat(ids(kb.search("de", "numéro", null, 4))).first().isEqualTo("changer-numero");
    }

    @Test
    void seuilDeScoreFiltre() {
        KnowledgeBase strict = new KnowledgeBase(new KbCorpus(Map.of("fr", kbArticles("fr"), "en", kbArticles("en"))), 1000.0);
        assertThat(strict.search("fr", "numéro", null, 4)).isEmpty();
    }

    @Test
    void listeDesRoutesAutorisees() {
        assertThat(kb.routeWhitelist()).containsExactlyInAnyOrder("/profile/edit/phone", "/trips/create");
    }

    private static List<KbArticle> kbArticles(String locale) {
        return List.of(new KbArticle(locale, "changer-numero", "Numéro", List.of(), List.of(), "numéro"));
    }

    private static List<String> ids(List<KbHit> hits) {
        return hits.stream().map(h -> h.article().id()).toList();
    }
}

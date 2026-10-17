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
    void relanceCourteTrouveLeTermeSeulementDansLaQuestionPrecedente() {
        // « portable » n'existe que dans la question précédente : sans elle, rien ne doit remonter.
        List<KbHit> hits = kb.search("fr", "et pour ça ?", "mon portable", 4);
        assertThat(ids(hits)).first().isEqualTo("changer-numero");
        assertThat(hits.get(0).score()).isPositive();
        assertThat(kb.search("fr", "et pour ça ?", null, 4)).isEmpty();
    }

    @Test
    void relanceCourteEnAnglaisProfiteDeLaQuestionPrecedente() {
        // « and in French ? » n'a aucun terme utile : seule la question précédente fait remonter la fiche.
        assertThat(ids(kb.search("en", "and in French?", "how do I change my phone number", 4)))
                .first().isEqualTo("changer-numero");
    }

    @Test
    void questionCompleteSurUnAutreSujetNeRamenePasLesFichesDeLAncienSujet() {
        // Plus de 4 mots : ce n'est plus une relance, la question précédente ne doit pas polluer les fiches.
        List<String> found = ids(kb.search("fr", "je voudrais annuler définitivement mon trajet prévu demain matin",
                "numéro portable", 4));
        assertThat(found).contains("annuler-trajet").doesNotContain("changer-numero");
    }

    @Test
    void quatreMotsRestentUneRelanceEtCinqNeLeSontPlus() {
        assertThat(ids(kb.search("fr", "annuler trajet prévu demain", "numéro portable", 4)))
                .contains("changer-numero");
        assertThat(ids(kb.search("fr", "annuler trajet prévu demain matin", "numéro portable", 4)))
                .doesNotContain("changer-numero");
    }

    @Test
    void questionCourantePrimeSurLaQuestionPrecedente() {
        // « annuler » (question courante, poids 1) doit battre « numéro portable » (précédente, poids 0.5).
        List<String> found = ids(kb.search("fr", "annuler", "numéro portable", 4));
        assertThat(found).contains("annuler-trajet", "changer-numero");
        assertThat(found.indexOf("annuler-trajet")).isLessThan(found.indexOf("changer-numero"));
    }

    @Test
    void relanceCourteJusquAQuatreTermesUtiles() {
        // Mots vides retirés : « et si je suis voyageur ? » ne garde que « voyageur ».
        assertThat(kb.isShortFollowUp("fr", "et si je suis voyageur ?")).isTrue();
        assertThat(kb.isShortFollowUp("fr", "annuler trajet prévu demain")).isTrue();
        assertThat(kb.isShortFollowUp("fr", "annuler trajet prévu demain matin")).isFalse();
        assertThat(kb.isShortFollowUp("en", "what if I am a traveler?")).isTrue();
        assertThat(kb.isShortFollowUp("en", "how do I cancel my planned trip tomorrow morning")).isFalse();
        assertThat(kb.isShortFollowUp("de", "?")).isTrue();
        assertThat(kb.isShortFollowUp("fr", null)).isTrue();
    }

    @Test
    void limiteNulleOuNegativeRetourneVide() {
        assertThat(kb.search("fr", "trajet", null, 0)).isEmpty();
        assertThat(kb.search("fr", "trajet", null, -1)).isEmpty();
    }

    @Test
    void anglaisIgnoreLesAccents() {
        KnowledgeBase cafe = new KnowledgeBase(new KbCorpus(Map.of("en", List.of(
                new KbArticle("en", "cafe-ouvert", "Café opening hours", List.of("café"), List.of(),
                        "The café is open every day.")))), 0.0);
        assertThat(ids(cafe.search("en", "cafe", null, 4))).first().isEqualTo("cafe-ouvert");
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

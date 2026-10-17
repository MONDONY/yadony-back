package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseFichesTest {

    /**
     * Le seuil de production, lu dans application.yml : le test ne dérive pas si on le règle. Mesures du
     * 16/10 sur les vraies fiches : questions complètes 16 à 30 ; mots seuls 8,7 à 13 ; hors-sujet
     * écartés au plus 6,1 ; hors-sujet qui passent encore 6,9 à 11,6 (ex. « meilleur téléphone » 10,1).
     */
    private static double productionMinScore() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return Double.parseDouble(yaml.getObject().getProperty("yadony.assistant.min-score"));
    }

    private static KnowledgeBase productionKb() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        return new KnowledgeBase(corpus, productionMinScore());
    }

    private static String firstHit(KnowledgeBase kb, String locale, String question) {
        List<KbHit> hits = kb.search(locale, question, null, 4);
        assertThat(hits).as("fiches pour « %s »", question).isNotEmpty();
        return hits.get(0).article().id();
    }

    @Test
    void lesFichesDuDepotSontValides() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        assertThat(corpus.articles("fr")).hasSizeGreaterThanOrEqualTo(50);
        assertThat(corpus.articles("en")).hasSameSizeAs(corpus.articles("fr"));
    }

    @Test
    void lesQuestionsFrequentesTrouventUneFiche() {
        KnowledgeBase kb = productionKb();
        assertThat(kb.search("fr", "Comment envoyer un colis ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Comment proposer un trajet ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Quand le voyageur est-il payé ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Comment vérifier mon identité ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Où je change mon numéro ?", null, 4)).first()
                .extracting(h -> h.article().id()).isEqualTo("changer-numero");
        assertThat(kb.search("en", "How do I send a parcel?", null, 4)).isNotEmpty();
    }

    @Test
    void chaqueFicheFrExisteEnAnglaisAvecLesMemesRoutes() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        Map<String, List<String>> frRoutes = cheminsParId(corpus.articles("fr"));
        Map<String, List<String>> enRoutes = cheminsParId(corpus.articles("en"));
        assertThat(enRoutes.keySet()).isEqualTo(frRoutes.keySet());
        frRoutes.forEach((id, chemins) ->
                assertThat(enRoutes.get(id)).as("routes de la fiche %s", id).containsExactlyInAnyOrderElementsOf(chemins));
    }

    @Test
    void lesNouvellesFichesSontPresentesEnFrancaisEtEnAnglais() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            assertThat(corpus.articles(locale)).extracting(KbArticle::id)
                    .contains("securite-paiements", "donnees-personnelles", "douane", "villes-desservies");
        }
    }

    @Test
    void lesQuestionsReellesTrouventLeurFicheEnTete() {
        KnowledgeBase kb = productionKb();
        Map<String, String> fr = new LinkedHashMap<>();
        fr.put("Comment changer mon numéro ?", "changer-numero");
        fr.put("Quand le voyageur est-il payé ?", "quand-paye-voyageur");
        fr.put("Comment annuler mon trajet ?", "annuler-trajet");
        fr.put("Comment vérifier mon identité ?", "verifier-identite");
        fr.put("Comment ouvrir un litige ?", "ouvrir-litige");
        fr.put("Comment supprimer mon compte ?", "supprimer-compte");
        fr.put("Le destinataire est absent, que faire ?", "destinataire-absent");
        fr.put("Comment recharger mon solde ?", "recharger-solde");
        fr.put("Quelle est la commission de Yadony ?", "commission");
        fr.put("Comment scanner le QR code du colis ?", "scanner-qr");
        fr.forEach((question, id) -> assertThat(firstHit(kb, "fr", question)).as(question).isEqualTo(id));

        Map<String, String> en = new LinkedHashMap<>();
        en.put("How do I change my phone number?", "changer-numero");
        en.put("When is the traveler paid?", "quand-paye-voyageur");
        en.put("How do I cancel my trip?", "annuler-trajet");
        en.put("How do I verify my identity?", "verifier-identite");
        en.forEach((question, id) -> assertThat(firstHit(kb, "en", question)).as(question).isEqualTo(id));
    }

    @Test
    void lesQuestionsDesNouvellesFichesTrouventLaBonneFiche() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Quelles villes sont desservies ?")).isEqualTo("villes-desservies");
        assertThat(firstHit(kb, "fr", "Faut-il déclarer le colis à la douane ?")).isEqualTo("douane");
        assertThat(firstHit(kb, "fr", "Mes données sont-elles revendues ?")).isEqualTo("donnees-personnelles");
        assertThat(firstHit(kb, "fr", "Le paiement par carte est-il sécurisé ?")).isEqualTo("securite-paiements");
        assertThat(firstHit(kb, "en", "Which cities are served?")).isEqualTo("villes-desservies");
        assertThat(firstHit(kb, "en", "Do I need to declare the parcel to customs?")).isEqualTo("douane");
        assertThat(firstHit(kb, "en", "Is my data sold?")).isEqualTo("donnees-personnelles");
        assertThat(firstHit(kb, "en", "Is card payment secure?")).isEqualTo("securite-paiements");
    }

    @Test
    void lesMotsSeulsUsuelsPassentLeSeuil() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "remboursement")).isEqualTo("remboursement");
        assertThat(firstHit(kb, "fr", "litige")).isEqualTo("ouvrir-litige");
        assertThat(firstHit(kb, "fr", "numéro")).isEqualTo("changer-numero");
        assertThat(firstHit(kb, "fr", "paiement")).isEqualTo("securite-paiements");
        assertThat(firstHit(kb, "en", "dispute")).isEqualTo("ouvrir-litige");
    }

    @Test
    void lesQuestionsHorsSujetNeRamenentAucuneFiche() {
        KnowledgeBase kb = productionKb();
        for (String question : List.of(
                "Quelle est la météo à Dakar demain ?",
                "Recette du thiéboudienne",
                "Qui a gagné la CAN ?",
                "Comment réparer ma voiture ?",
                "Qui est le président de la France ?",
                "Raconte-moi une blague")) {
            assertThat(kb.search("fr", question, null, 4)).as(question).isEmpty();
        }
        for (String question : List.of(
                "What is the weather in Dakar tomorrow?",
                "Who won the world cup?",
                "What is the capital of Australia?",
                "Who is the president of France?")) {
            assertThat(kb.search("en", question, null, 4)).as(question).isEmpty();
        }
    }

    @Test
    void uneRelanceEnChaineNeTrouveRienAvecLaSeuleQuestionPrecedente() {
        // FLUTTER-QV : « comment je fais ? » après « et après ? » ne passe pas le seuil, même avec la question
        // précédente à demi-poids ; la question d'avant (« Comment recharger mon solde ? ») ramène la fiche.
        // AssistantService relance alors avec les deux dernières questions utilisateur.
        KnowledgeBase kb = productionKb();
        assertThat(kb.search("fr", "comment je fais ?", "et après ?", 4)).isEmpty();
        assertThat(kb.search("fr", "comment je fais ?\net après ?\nComment recharger mon solde ?", null, 4))
                .first().extracting(h -> h.article().id()).isEqualTo("recharger-solde");
    }

    @Test
    void laDeuxiemeFicheDeLaDouaneNeVoleJamaisLaPremierePlace() {
        // « déclarer » est aussi dans « Valeur déclarée » : la fiche douane doit rester devant.
        List<String> ids = productionKb().search("fr", "Faut-il déclarer le colis à la douane ?", null, 4)
                .stream().map(h -> h.article().id()).toList();
        assertThat(ids).startsWith("douane");
    }

    @Test
    void livraisonConfirmeeCiteLeTitreReelDeLaFicheDestinataireAbsent() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            String title = corpus.articles(locale).stream().filter(a -> a.id().equals("destinataire-absent"))
                    .findFirst().orElseThrow().title();
            KbArticle livraison = corpus.articles(locale).stream().filter(a -> a.id().equals("livraison-confirmee"))
                    .findFirst().orElseThrow();
            assertThat(livraison.body()).as(locale).contains(title);
        }
    }

    @Test
    void moyensDePaiementNAnnoncentPasUnNombreQuIlsContredisent() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle moyens = corpus.articles(locale).stream().filter(a -> a.id().equals("moyens-paiement"))
                    .findFirst().orElseThrow();
            assertThat(moyens.body()).as(locale).doesNotContainIgnoringCase("trois moyens")
                    .doesNotContainIgnoringCase("three methods");
        }
    }

    @Test
    void kycDelaiNeSeCaleSurLeMotMinutes() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle delai = corpus.articles(locale).stream().filter(a -> a.id().equals("kyc-delai"))
                    .findFirst().orElseThrow();
            assertThat(delai.keywords()).as(locale).doesNotContain("minutes");
        }
    }

    private static Map<String, List<String>> cheminsParId(List<KbArticle> articles) {
        return articles.stream().collect(Collectors.toMap(KbArticle::id,
                a -> a.routes().stream().map(KbRoute::path).toList(), (x, y) -> x));
    }
}

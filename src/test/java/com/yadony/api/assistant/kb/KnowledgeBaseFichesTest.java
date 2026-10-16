package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseFichesTest {

    @Test
    void lesFichesDuDepotSontValides() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        assertThat(corpus.articles("fr")).hasSizeGreaterThanOrEqualTo(50);
        assertThat(corpus.articles("en")).hasSameSizeAs(corpus.articles("fr"));
    }

    @Test
    void lesQuestionsFrequentesTrouventUneFiche() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        KnowledgeBase kb = new KnowledgeBase(corpus, 0.5);
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

    private static Map<String, List<String>> cheminsParId(List<KbArticle> articles) {
        return articles.stream().collect(Collectors.toMap(KbArticle::id,
                a -> a.routes().stream().map(KbRoute::path).toList(), (x, y) -> x));
    }
}

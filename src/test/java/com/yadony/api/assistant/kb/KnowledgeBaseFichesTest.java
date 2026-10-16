package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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
}

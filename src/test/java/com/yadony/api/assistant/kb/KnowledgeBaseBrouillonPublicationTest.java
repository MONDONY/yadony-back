package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retest R2 (3.8) : la fiche de tête de « pourquoi je ne peux plus publier » est la fiche dédiée, qui dit dès
 * ses premières lignes qu'un brouillon reste possible.
 */
class KnowledgeBaseBrouillonPublicationTest {

    private static KnowledgeBase productionKb() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        double min = Double.parseDouble(yaml.getObject().getProperty("yadony.assistant.min-score"));
        return new KnowledgeBase(KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(),
                "classpath:assistant/kb"), min);
    }

    @Test
    void ficheDeTeteFrMentionneLeBrouillon() {
        List<KbHit> hits = productionKb().search("fr", "Pourquoi je ne peux plus publier de trajet ?", null, 4);
        assertThat(hits.get(0).article().id()).isEqualTo("limite-mensuelle-trajets");
        assertThat(hits.get(0).article().body()).startsWith("La limite mensuelle est fixée par l'équipe");
        assertThat(hits.get(0).article().body()).containsIgnoringCase("brouillon");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void ficheDeTeteEnMentionneLeDraft() {
        List<KbHit> hits = productionKb().search("en", "Why can't I publish a trip anymore?", null, 4);
        assertThat(hits.get(0).article().id()).isEqualTo("limite-mensuelle-trajets");
        assertThat(hits.get(0).article().body()).startsWith("The monthly limit is set by the Yadony team");
        assertThat(hits.get(0).article().body()).containsIgnoringCase("draft");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void variantesDeLaQuestion_ramenentLaMemeFiche() {
        KnowledgeBase kb = productionKb();
        for (String q : List.of("Je ne peux plus publier de trajet", "Pourquoi la publication est bloquée ?",
                "J'ai atteint la limite mensuelle de trajets")) {
            assertThat(kb.search("fr", q, null, 4).get(0).article().id()).as(q).isEqualTo("limite-mensuelle-trajets");
        }
        for (String q : List.of("I can't publish a trip", "Why is publishing blocked?",
                "I reached the monthly trip limit")) {
            assertThat(kb.search("en", q, null, 4).get(0).article().id()).as(q).isEqualTo("limite-mensuelle-trajets");
        }
    }
}

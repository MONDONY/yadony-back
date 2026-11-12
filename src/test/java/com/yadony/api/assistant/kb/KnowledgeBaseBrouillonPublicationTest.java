package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Retest 3.8 : la fiche de tête de « pourquoi je ne peux plus publier » dit qu'un brouillon reste possible. */
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
        System.out.println("TETE-FR " + hits.get(0).article().id() + " " + hits.get(0).score());
        assertThat(hits.get(0).article().body()).containsIgnoringCase("brouillon");
    }

    @Test
    void ficheDeTeteEnMentionneLeDraft() {
        List<KbHit> hits = productionKb().search("en", "Why can't I publish a trip anymore?", null, 4);
        System.out.println("TETE-EN " + hits.get(0).article().id() + " " + hits.get(0).score());
        assertThat(hits.get(0).article().body()).containsIgnoringCase("draft");
    }
}

package com.yadony.api.matching.ranking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripRankingPropertiesTest {

    private TripRankingProperties bind(Map<String, Object> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bind("yadony.ranking", TripRankingProperties.class)
                .get();
    }

    @Test
    void defaults_matchSpec() {
        TripRankingProperties defaults = bind(Map.of("yadony.ranking.similar-max", "0"));
        assertThat(defaults.weights().reliability()).isEqualTo(30);
        assertThat(defaults.weights().rating()).isEqualTo(25);
        assertThat(defaults.weights().deliveries()).isEqualTo(20);
        assertThat(defaults.weights().freshness()).isEqualTo(15);
        assertThat(defaults.weights().onlinePayment()).isEqualTo(10);
        assertThat(defaults.exactThreshold()).isEqualTo(7);
        assertThat(defaults.similarMax()).isEqualTo(20);
        assertThat(defaults.maxCandidates()).isEqualTo(1000);
        assertThat(defaults.maxCandidatesWithoutCity()).isEqualTo(300);
        assertThat(defaults.weightSum()).isEqualTo(100.0);
    }

    @Test
    void bind_overridesValues() {
        TripRankingProperties p = bind(Map.of(
                "yadony.ranking.weights.reliability", "40",
                "yadony.ranking.similar-max", "5",
                "yadony.ranking.max-candidates-without-city", "150"));
        assertThat(p.weights().reliability()).isEqualTo(40);
        assertThat(p.maxCandidatesWithoutCity()).isEqualTo(150);
        assertThat(p.weights().rating()).isEqualTo(25);
        assertThat(p.similarMax()).isEqualTo(5);
    }

    @Test
    void weightZero_isKept() {
        TripRankingProperties p = bind(Map.of("yadony.ranking.weights.freshness", "0"));
        assertThat(p.weights().freshness()).isZero();
        assertThat(p.weights().reliability()).isEqualTo(30);
        assertThat(p.weightSum()).isEqualTo(85.0);
    }

    @Test
    void negativeWeight_isRejected() {
        assertThatThrownBy(() -> bind(Map.of("yadony.ranking.weights.rating", "-1")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}

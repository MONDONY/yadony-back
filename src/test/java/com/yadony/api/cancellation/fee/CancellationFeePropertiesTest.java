package com.yadony.api.cancellation.fee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Interrupteur des frais d'annulation (éteint par défaut, date d'effet) et barème validé au démarrage : une
 * configuration invalide empêche l'application de démarrer, jamais un taux hors de [0, 1] en production.
 */
@DisplayName("CancellationFeeProperties : interrupteur, date d'effet et barème validé")
class CancellationFeePropertiesTest {

    private static final List<CancellationFeeProperties.Tier> TIERS = List.of(
            new CancellationFeeProperties.Tier(5, BigDecimal.ZERO),
            new CancellationFeeProperties.Tier(3, new BigDecimal("0.10")),
            new CancellationFeeProperties.Tier(1, new BigDecimal("0.20")),
            new CancellationFeeProperties.Tier(0, new BigDecimal("0.30")));

    @Configuration
    @EnableConfigurationProperties(CancellationFeeProperties.class)
    static class Bound {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bound.class);

    // ── interrupteur et date d'effet ────────────────────────────────────────────────────────────────

    @Test
    void defaults_areOff_withoutEffectiveDate() {
        CancellationFeeProperties props = new CancellationFeeProperties(false, null, null, null);

        assertThat(props.enabled()).isFalse();
        assertThat(props.effectiveFrom()).isNull();
        assertThat(props.capEur()).isEqualByComparingTo("30");
        assertThat(props.tiers()).extracting(CancellationFeeProperties.Tier::minDaysBefore).containsExactly(5, 3, 1, 0);
    }

    @Test
    void scaleOnlyConstructor_isOff() {
        CancellationFeeProperties props = new CancellationFeeProperties(new BigDecimal("25"), TIERS);

        assertThat(props.enabled()).isFalse();
        assertThat(props.effectiveFrom()).isNull();
        assertThat(props.capEur()).isEqualByComparingTo("25");
    }

    @Test
    void applicationYaml_defaultsAreOff_andOverridableByEnvironment() throws Exception {
        assertThat(bindApplicationYaml(Map.of()).enabled()).isFalse();
        assertThat(bindApplicationYaml(Map.of()).effectiveFrom()).isNull();

        CancellationFeeProperties on = bindApplicationYaml(Map.of(
                "YADONY_CANCELLATION_FEE_ENABLED", "true",
                "YADONY_CANCELLATION_FEE_EFFECTIVE_FROM", "2026-12-01T00:00:00Z"));
        assertThat(on.enabled()).isTrue();
        assertThat(on.effectiveFrom()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(on.capEur()).isEqualByComparingTo("30");
    }

    @Test
    void contextRunner_bindsSwitchAndEffectiveDate() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true",
                        "yadony.cancellation-fee.effective-from=2026-12-01T00:00:00Z")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CancellationFeeProperties props = context.getBean(CancellationFeeProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.effectiveFrom()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
                });
    }

    @Test
    void contextRunner_emptyEffectiveDate_isNull() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true", "yadony.cancellation-fee.effective-from=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CancellationFeeProperties.class).effectiveFrom()).isNull();
                });
    }

    // ── barème validé au démarrage ──────────────────────────────────────────────────────────────────

    @Test
    void rateAboveOne_refused() {
        assertThatThrownBy(() -> props(new BigDecimal("30"), List.of(
                new CancellationFeeProperties.Tier(3, new BigDecimal("1.5")),
                new CancellationFeeProperties.Tier(0, new BigDecimal("0.30")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yadony.cancellation-fee.tiers")
                .hasMessageContaining("1.5");
    }

    @Test
    void negativeRate_refused() {
        assertThatThrownBy(() -> props(new BigDecimal("30"), List.of(
                new CancellationFeeProperties.Tier(3, new BigDecimal("-0.10")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate");
    }

    @Test
    void missingRate_refused() {
        assertThatThrownBy(() -> props(new BigDecimal("30"), List.of(new CancellationFeeProperties.Tier(3, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate");
    }

    @Test
    void negativeCap_refused() {
        assertThatThrownBy(() -> props(new BigDecimal("-1"), TIERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cap-eur");
    }

    @Test
    void duplicateMinDaysBefore_refused() {
        assertThatThrownBy(() -> props(new BigDecimal("30"), List.of(
                new CancellationFeeProperties.Tier(3, new BigDecimal("0.10")),
                new CancellationFeeProperties.Tier(3, new BigDecimal("0.20")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-days-before")
                .hasMessageContaining("3");
    }

    @Test
    void negativeMinDaysBefore_refused() {
        assertThatThrownBy(() -> props(new BigDecimal("30"), List.of(
                new CancellationFeeProperties.Tier(-1, new BigDecimal("0.10")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-days-before");
    }

    @Test
    void boundaryValues_accepted() {
        CancellationFeeProperties props = props(BigDecimal.ZERO, List.of(
                new CancellationFeeProperties.Tier(2, BigDecimal.ZERO),
                new CancellationFeeProperties.Tier(0, BigDecimal.ONE)));

        assertThat(props.capEur()).isEqualByComparingTo("0");
        assertThat(props.tiers()).hasSize(2);
    }

    @Test
    void invalidScale_applicationRefusesToStart_withAClearMessage() {
        runner.withPropertyValues(
                        "yadony.cancellation-fee.tiers[0].min-days-before=3",
                        "yadony.cancellation-fee.tiers[0].rate=1.5",
                        "yadony.cancellation-fee.tiers[1].min-days-before=0",
                        "yadony.cancellation-fee.tiers[1].rate=0.30")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("yadony.cancellation-fee.tiers")
                            .hasMessageContaining("1.5");
                });
    }

    @Test
    void duplicateTiers_applicationRefusesToStart() {
        runner.withPropertyValues(
                        "yadony.cancellation-fee.tiers[0].min-days-before=1",
                        "yadony.cancellation-fee.tiers[0].rate=0.10",
                        "yadony.cancellation-fee.tiers[1].min-days-before=1",
                        "yadony.cancellation-fee.tiers[1].rate=0.20")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("min-days-before");
                });
    }

    @Test
    void negativeCap_applicationRefusesToStart() {
        runner.withPropertyValues("yadony.cancellation-fee.cap-eur=-5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("cap-eur");
                });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static CancellationFeeProperties props(BigDecimal cap, List<CancellationFeeProperties.Tier> tiers) {
        return new CancellationFeeProperties(true, Instant.parse("2026-12-01T00:00:00Z"), cap, tiers);
    }

    /** {@code application.yml} réel, ses variables d'environnement résolues comme au démarrage. */
    private static CancellationFeeProperties bindApplicationYaml(Map<String, Object> environment) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test-env", environment));
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env))
                .bind("yadony.cancellation-fee", CancellationFeeProperties.class)
                .get();
    }
}

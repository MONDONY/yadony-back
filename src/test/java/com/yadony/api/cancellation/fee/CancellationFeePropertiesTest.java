package com.yadony.api.cancellation.fee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // ── date d'effet stricte (revue tâche 8, tour 1) : jamais lue comme des millisecondes depuis 1970 ────────

    @ParameterizedTest
    @ValueSource(strings = {"20261215", "2026-12-15", "not-a-date", "2026-12-15T00:00:00", "1765756800000"})
    void effectiveFromNotAnIsoInstant_applicationRefusesToStart(String value) {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true", "yadony.cancellation-fee.effective-from=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("yadony.cancellation-fee.effective-from")
                            .hasMessageContaining(value)
                            .hasMessageContaining("2026-12-15T00:00:00Z");
                });
    }

    @Test
    void effectiveFromInvalid_refusedEvenWhenOff() {
        // Une valeur illisible est une faute de configuration, allumée ou non : on la voit avant le jour où l'on allume.
        runner.withPropertyValues("yadony.cancellation-fee.enabled=false", "yadony.cancellation-fee.effective-from=20261215")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void effectiveFromUtc_accepted() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true",
                        "yadony.cancellation-fee.effective-from=2026-12-15T00:00:00Z")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CancellationFeeProperties.class).effectiveFrom())
                            .isEqualTo(Instant.parse("2026-12-15T00:00:00Z"));
                });
    }

    @Test
    void effectiveFromWithOffset_acceptedAsTheSameInstant() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true",
                        "yadony.cancellation-fee.effective-from=2026-12-15T00:00:00+01:00")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CancellationFeeProperties.class).effectiveFrom())
                            .isEqualTo(Instant.parse("2026-12-14T23:00:00Z"));
                });
    }

    @Test
    void effectiveFromBeforeTheFeatureExisted_enabled_applicationRefusesToStart() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true",
                        "yadony.cancellation-fee.effective-from=2020-01-01T00:00:00Z")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("yadony.cancellation-fee.effective-from")
                            .hasMessageContaining("2026-11-01T00:00:00Z");
                });
        // La borne elle-même est acceptée.
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true",
                        "yadony.cancellation-fee.effective-from=2026-11-01T00:00:00Z")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void effectiveFromInThePast_onlyWithTheTestOnlyAllowance() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true",
                        "yadony.cancellation-fee.effective-from=2020-01-01T00:00:00Z",
                        "yadony.cancellation-fee.allow-past-effective-from=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CancellationFeeProperties.class).effectiveFrom())
                            .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
                });
    }

    @Test
    void effectiveFromInThePast_off_isInert_andStarts() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=false",
                        "yadony.cancellation-fee.effective-from=2020-01-01T00:00:00Z")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void effectiveFromUnset_isOff() {
        runner.withPropertyValues("yadony.cancellation-fee.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CancellationFeeProperties.class).effectiveFrom()).isNull();
                });
    }

    @Test
    void pastAllowance_onlyInTheTestProfiles_neverInApplicationFilesNorWorkflows() throws Exception {
        // Seul le profil des tests de la fonction l'autorise (et les allume) ; la suite entière tourne éteinte.
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/resources/application-cancellation-fee.yml")))
                .contains("allow-past-effective-from: true").contains("enabled: true");
        java.util.List<java.nio.file.Path> forbidden = new java.util.ArrayList<>(java.util.List.of(
                java.nio.file.Path.of("src/test/resources/application-test.yml"),
                java.nio.file.Path.of("src/test/resources/application-e2e.yml"),
                // Ce qui alimente l'environnement des conteneurs déployés.
                java.nio.file.Path.of("docker-compose.prod.yml"), java.nio.file.Path.of("docker-compose.staging.yml"),
                java.nio.file.Path.of("docker-compose.dev.yml"), java.nio.file.Path.of(".env.example"),
                java.nio.file.Path.of(".env.prod.template"), java.nio.file.Path.of(".env.staging.template")));
        for (String root : new String[] {"src/main", ".github/workflows"}) {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(java.nio.file.Path.of(root))) {
                files.filter(java.nio.file.Files::isRegularFile).forEach(forbidden::add);
            }
        }
        assertThat(forbidden).as("src/main et les workflows parcourus").hasSizeGreaterThan(100);
        for (java.nio.file.Path file : forbidden) {
            String content = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            // Formes de la liaison souple de Spring : clé yaml, variable d'environnement.
            assertThat(content).as(file.toString()).doesNotContain("allow-past-effective-from")
                    .doesNotContain("allow_past_effective_from");
        }
    }

    @Test
    void sharedTestProfiles_leaveTheFeatureOff_likeProduction() throws Exception {
        for (String file : new String[] {"src/test/resources/application-test.yml",
                "src/test/resources/application-e2e.yml"}) {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            assertThat(content).as(file).doesNotContain("cancellation-fee:").doesNotContain("effective-from:");
        }
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

package com.yadony.api.assistant.quota;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V329 sur Postgres embarqué, toutes migrations Flyway et ddl-auto=validate : l'entité colle au schéma, et
 * les requêtes atomiques tiennent le quota sous concurrence avec les vrais verrous de ligne.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Assistant - quota des questions persisté (V329, Postgres)")
class AssistantUserDailyUsagePostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static EmbeddedPostgres postgres;

    @Autowired JpaAssistantUserDailyUsageStore store;
    @Autowired AssistantUserDailyUsageRepository repository;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    @DisplayName("le quota du jour tient exactement sous concurrence")
    void quotaHoldsUnderConcurrency() throws Exception {
        List<Integer> accordees = AssistantUserDailyUsageConcurrency.consumeConcurrently(store, "uid-concurrence",
                DAY, 30, 16, 10);
        assertThat(accordees).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 30).boxed().toList());
        assertThat(repository.findQuestions("uid-concurrence", DAY)).contains(30);
    }

    @Test
    @DisplayName("un compte sans ligne users (pas encore inscrit) est compté : pas de clé étrangère")
    void uidWithoutUserRowIsCounted() {
        assertThat(store.tryConsume("uid-pas-encore-inscrit", DAY, 5)).isEqualTo(1);
        assertThat(store.tryConsume("uid-pas-encore-inscrit", DAY, 5)).isEqualTo(2);
    }

    @Test
    @DisplayName("la purge nocturne garde les 30 derniers jours")
    void purgeKeepsLastThirtyDays() {
        LocalDate today = LocalDate.of(2026, 12, 1);
        store.tryConsume("uid-purge", today.minusDays(45), 5);
        store.tryConsume("uid-purge", today.minusDays(30), 5);
        new AssistantUserDailyUsageCleanupScheduler(repository,
                Clock.fixed(Instant.parse("2026-12-01T03:30:00Z"), ZoneOffset.UTC)).purgeOldDays();
        assertThat(repository.findQuestions("uid-purge", today.minusDays(45))).isEmpty();
        assertThat(repository.findQuestions("uid-purge", today.minusDays(30))).contains(1);
    }
}

package com.yadony.api.assistant.llm;

import com.yadony.api.assistant.provider.AssistantLlmProviderEntity;
import com.yadony.api.assistant.provider.AssistantLlmProviderRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V327 sur Postgres embarqué, toutes migrations Flyway et ddl-auto=validate : l'entité colle au schéma, et
 * les requêtes atomiques tiennent la limite sous concurrence avec les vrais verrous de ligne.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Assistant - compteurs du jour persistés (V327, Postgres)")
class ProviderDailyUsagePostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static EmbeddedPostgres postgres;

    @Autowired JpaProviderDailyUsageStore store;
    @Autowired AssistantLlmProviderRepository providers;

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

    /** Validé hors transaction de test : les réservations ont leur propre transaction et la clé étrangère doit le voir. */
    private UUID provider(String slug) {
        AssistantLlmProviderEntity p = new AssistantLlmProviderEntity();
        p.setName(slug); p.setSlug(slug); p.setBaseUrl("https://api.example.com/v1");
        p.setModel("m"); p.setApiKey("key_" + slug + "_1234"); p.setPriority(1);
        return providers.saveAndFlush(p).getId();
    }

    @Test
    @DisplayName("la limite du jour tient sous concurrence")
    void limitHoldsUnderConcurrency() throws Exception {
        UUID id = provider("claude-limite");
        int accordes = ProviderDailyUsageConcurrency.reserveConcurrently(store, id, DAY, 50, 16, 10);
        assertThat(accordes).isEqualTo(50);
        assertThat(store.read(List.of(id), DAY).get(id).requests()).isEqualTo(50);
    }

    @Test
    @DisplayName("réservations et succès concurrents donnent un total exact")
    void concurrentIncrementsAreExact() throws Exception {
        UUID id = provider("groq-total");
        int accordes = ProviderDailyUsageConcurrency.reserveConcurrently(store, id, DAY, ProviderDailyUsageStore.NO_LIMIT, 16, 20);
        ProviderDailyUsageConcurrency.runConcurrently(16, 5, () -> store.addSuccess(id, DAY));
        assertThat(accordes).isEqualTo(320);
        ProviderDailyUsageStore.DailyCount c = store.read(List.of(id), DAY).get(id);
        assertThat(c.requests()).isEqualTo(320);
        assertThat(c.successes()).isEqualTo(80);
    }

    @Test
    @DisplayName("un fournisseur inconnu est refusé par la clé étrangère")
    void unknownProviderIsRejected() {
        assertThatThrownBy(() -> store.tryReserve(UUID.randomUUID(), DAY, 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

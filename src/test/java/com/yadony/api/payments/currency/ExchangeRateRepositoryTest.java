package com.yadony.api.payments.currency;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tourne sur un PostgreSQL embarqué avec le vrai Flyway (comme les tests de
 * migration) : le profil de test désactive Flyway et génère le schéma H2 depuis
 * les entités JPA, donc l'amorçage des 7 devises et les contraintes CHECK de
 * V226 ne seraient vérifiés par rien d'autre.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExchangeRateRepositoryTest {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
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

    @Autowired
    private ExchangeRateRepository repository;

    @Test
    void allSevenSeedCurrenciesArePresentAfterMigration() {
        assertThat(repository.findAll()).hasSize(7);
        assertThat(repository.findAll())
                .extracting(ExchangeRateEntity::getCurrency)
                .containsExactlyInAnyOrder("EUR", "USD", "CAD", "GBP", "CHF", "XOF", "XAF");
    }

    @Test
    void xofUnitsPerEurIsExactlyTheFixedParity() {
        var xof = repository.findByCurrency("XOF");

        assertThat(xof).isPresent();
        assertThat(xof.get().getUnitsPerEur()).isEqualByComparingTo(new BigDecimal("655.957"));
    }

    @Test
    void negativeRateIsRejectedByTheCheckConstraint() {
        assertThatThrownBy(() -> repository.findByCurrency("XAF").ifPresent(existing -> {
            existing.setUnitsPerEur(new BigDecimal("-1"));
            repository.saveAndFlush(existing);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroRateIsRejectedByTheCheckConstraint() {
        assertThatThrownBy(() -> repository.findByCurrency("EUR").ifPresent(existing -> {
            existing.setUnitsPerEur(BigDecimal.ZERO);
            repository.saveAndFlush(existing);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }
}

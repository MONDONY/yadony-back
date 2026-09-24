package com.yadony.api.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code UserRepository.findPreferredLanguageById} sur Postgres embarqué avec les
 * migrations Flyway et {@code ddl-auto=validate} : valide au passage que le mapping
 * de {@link UserEntity#getPreferredLanguage()} correspond bien à la colonne V264.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("UserRepository — langue préférée (V264)")
class UserRepositoryPreferredLanguageTest {

    private static EmbeddedPostgres postgres;

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

    @Autowired UserRepository repository;
    @PersistenceContext EntityManager entityManager;

    private UserEntity newUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setUsername("user-" + UUID.randomUUID().toString().substring(0, 12));
        return repository.saveAndFlush(user);
    }

    @Test
    void findPreferredLanguageById_rendFrPourUnUtilisateurNeuf() {
        UserEntity user = newUser();
        entityManager.clear();

        Optional<String> result = repository.findPreferredLanguageById(user.getId());

        assertThat(result).contains("fr");
    }

    @Test
    void findPreferredLanguageById_rendEnApresMiseAJour() {
        UserEntity user = newUser();
        user.setPreferredLanguage(com.yadony.api.common.i18n.AppLanguage.EN);
        repository.saveAndFlush(user);
        entityManager.clear();

        Optional<String> result = repository.findPreferredLanguageById(user.getId());

        assertThat(result).contains("en");
    }

    @Test
    void findPreferredLanguageById_videPourUnIdInconnu() {
        Optional<String> result = repository.findPreferredLanguageById(UUID.randomUUID());

        assertThat(result).isEmpty();
    }
}

package com.yadony.api.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nombre de requêtes SQL émises pour charger des utilisateurs. {@code roles} et
 * {@code languages} sont des collections EAGER : sans précaution, chaque utilisateur
 * chargé coûte deux SELECT de plus (user_roles et user_languages lus ~335 000 fois
 * sur staging en six semaines, pour 88 comptes).
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("UserRepository — chargement des rôles et langues sans N+1")
class UserRepositoryFetchTest {

    @Autowired UserRepository repository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @PersistenceContext EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    private UserEntity newUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setUsername("user-" + UUID.randomUUID().toString().substring(0, 12));
        user.setRoles(new HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        user.setLanguages(new HashSet<>(Set.of("fr", "en")));
        return repository.saveAndFlush(user);
    }

    @Test
    void findByFirebaseUid_chargeRolesEtLanguesEnUneSeuleRequete() {
        UserEntity saved = newUser();
        entityManager.clear();
        statistics.clear();

        UserEntity loaded = repository.findByFirebaseUid(saved.getFirebaseUid()).orElseThrow();

        assertThat(loaded.getRoles()).containsExactlyInAnyOrder(Role.SENDER, Role.TRAVELER);
        assertThat(loaded.getLanguages()).containsExactlyInAnyOrder("fr", "en");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void findAllById_chargeLesCollectionsParLotEtNonParUtilisateur() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(newUser().getId());
        }
        entityManager.clear();
        statistics.clear();

        List<UserEntity> loaded = repository.findAllById(ids);

        assertThat(loaded).hasSize(5);
        assertThat(loaded).allSatisfy(u -> {
            assertThat(u.getRoles()).hasSize(2);
            assertThat(u.getLanguages()).hasSize(2);
        });
        // 1 SELECT users + 1 SELECT user_roles par lot + 1 SELECT user_languages par lot,
        // au lieu de 1 + 5 + 5 sans chargement par lots.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}

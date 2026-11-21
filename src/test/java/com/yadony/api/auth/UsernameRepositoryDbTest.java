package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Accesseurs d'identifiant : casse, soft delete, réservation")
class UsernameRepositoryDbTest {

    @Autowired private UserRepository users;
    @Autowired private ReleasedUsernameRepository released;
    @Autowired private TestEntityManager em;

    private UserEntity user(String username, boolean deleted) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        if (deleted) u.softDelete();
        return em.persistAndFlush(u);
    }

    @Test
    @DisplayName("findByUsernameIgnoreCase retrouve « Awa » avec « awa »")
    void findsIgnoringCase() {
        UserEntity awa = user("Awa", false);
        em.clear();
        assertThat(users.findByUsernameIgnoreCase("awa")).get().extracting(UserEntity::getId).isEqualTo(awa.getId());
    }

    @Test
    @DisplayName("findByUsernameIgnoreCase ignore un compte supprimé")
    void findIgnoresSoftDeleted() {
        user("Koffi", true);
        em.clear();
        assertThat(users.findByUsernameIgnoreCase("koffi")).isEmpty();
    }

    @Test
    @DisplayName("existsByUsernameIgnoreCase inclut les comptes supprimés")
    void existsIncludesSoftDeleted() {
        user("Moussa", true);
        em.clear();
        assertThat(users.existsByUsernameIgnoreCase("moussa")).isTrue();
        assertThat(users.existsByUsernameIgnoreCase("inconnu")).isFalse();
    }

    @Test
    @DisplayName("usernameChangedAt est persisté")
    void usernameChangedAtPersisted() {
        UserEntity u = user("Fanta", false);
        OffsetDateTime now = OffsetDateTime.now();
        u.setUsernameChangedAt(now);
        em.persistAndFlush(u);
        em.clear();
        assertThat(users.findById(u.getId()).orElseThrow().getUsernameChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("réservation : existsByUsernameAndReleasedAtAfter respecte le seuil")
    void releasedReservationWindow() {
        OffsetDateTime now = OffsetDateTime.now();
        released.saveAndFlush(new ReleasedUsernameEntity("ancien", now.minusDays(10), UUID.randomUUID()));
        released.saveAndFlush(new ReleasedUsernameEntity("recent", now.minusDays(1), UUID.randomUUID()));
        OffsetDateTime threshold = now.minusDays(5);

        assertThat(released.existsByUsernameAndReleasedAtAfter("recent", threshold)).isTrue();
        assertThat(released.existsByUsernameAndReleasedAtAfter("ancien", threshold)).isFalse();
        assertThat(released.existsByUsernameAndReleasedAtAfter("absent", threshold)).isFalse();
    }

    @Test
    @DisplayName("deleteByReleasedAtBefore purge et renvoie le nombre de lignes")
    void purgeReturnsCount() {
        OffsetDateTime now = OffsetDateTime.now();
        released.saveAndFlush(new ReleasedUsernameEntity("a", now.minusDays(40), UUID.randomUUID()));
        released.saveAndFlush(new ReleasedUsernameEntity("b", now.minusDays(35), UUID.randomUUID()));
        released.saveAndFlush(new ReleasedUsernameEntity("c", now.minusDays(1), UUID.randomUUID()));

        assertThat(released.deleteByReleasedAtBefore(now.minusDays(30))).isEqualTo(2);
        assertThat(released.count()).isEqualTo(1);
    }
}

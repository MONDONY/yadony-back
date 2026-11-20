package com.yadony.api.likes;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TripLikeRepositoryTest {

    @Autowired TripLikeRepository repository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    private UUID newUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setUsername("user-" + UUID.randomUUID().toString().substring(0, 12));
        user.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        user.setLanguages(new HashSet<>(Set.of("fr")));
        return userRepository.saveAndFlush(user).getId();
    }

    private long rows(UUID tripId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM trip_likes WHERE trip_id = :t")
                .setParameter("t", tripId).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("insertIfAbsent deux fois : une seule ligne")
    void insertIfAbsent_deux_fois_ne_cree_qu_une_ligne() {
        UUID user = newUser();
        UUID trip = UUID.randomUUID();

        assertThat(repository.insertIfAbsent(UUID.randomUUID(), user, trip)).isEqualTo(1);
        assertThat(repository.insertIfAbsent(UUID.randomUUID(), user, trip)).isZero();

        assertThat(rows(trip)).isEqualTo(1);
        assertThat(repository.existsByUserIdAndTripId(user, trip)).isTrue();
        assertThat(repository.countActive(trip)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteActive est idempotent")
    void deleteActive_est_idempotent() {
        UUID user = newUser();
        UUID trip = UUID.randomUUID();
        repository.insertIfAbsent(UUID.randomUUID(), user, trip);

        assertThat(repository.deleteActive(user, trip)).isEqualTo(1);
        assertThat(repository.deleteActive(user, trip)).isZero();

        assertThat(rows(trip)).isZero();
        assertThat(repository.existsByUserIdAndTripId(user, trip)).isFalse();
    }

    @Test
    @DisplayName("les comptes supprimés ne comptent ni dans countByTripIds ni dans countActive")
    void countByTripIds_ignore_les_comptes_supprimes() {
        UUID alive = newUser();
        UUID gone = newUser();
        UUID trip = UUID.randomUUID();
        repository.insertIfAbsent(UUID.randomUUID(), alive, trip);
        repository.insertIfAbsent(UUID.randomUUID(), gone, trip);
        entityManager.createNativeQuery("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                .setParameter("id", gone).executeUpdate();
        entityManager.clear();

        List<Object[]> counts = repository.countByTripIds(List.of(trip));

        assertThat(counts).hasSize(1);
        assertThat(counts.get(0)[0]).isEqualTo(trip);
        assertThat(((Number) counts.get(0)[1]).longValue()).isEqualTo(1);
        assertThat(repository.countActive(trip)).isEqualTo(1);
    }

    private void setStatus(UUID id, String status) {
        entityManager.createNativeQuery("UPDATE users SET status = :s WHERE id = :id")
                .setParameter("s", status).setParameter("id", id).executeUpdate();
        entityManager.clear();
    }

    @Test
    @DisplayName("les comptes bannis ou suspendus ne comptent pas, les comptes actifs oui")
    void counts_ignorent_les_comptes_bannis_ou_suspendus() {
        UUID active = newUser();
        UUID banned = newUser();
        UUID suspended = newUser();
        UUID trip = UUID.randomUUID();
        for (UUID u : List.of(active, banned, suspended)) {
            repository.insertIfAbsent(UUID.randomUUID(), u, trip);
        }
        setStatus(banned, "BANNED");
        setStatus(suspended, "SUSPENDED");

        assertThat(repository.countActive(trip)).isEqualTo(1);
        List<Object[]> counts = repository.countByTripIds(List.of(trip));
        assertThat(counts).hasSize(1);
        assertThat(((Number) counts.get(0)[1]).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("un trajet dont tous les j'aime viennent de comptes bannis n'apparait pas")
    void countByTripIds_omet_le_trajet_si_seuls_des_bannis_ont_aime() {
        UUID banned = newUser();
        UUID trip = UUID.randomUUID();
        repository.insertIfAbsent(UUID.randomUUID(), banned, trip);
        setStatus(banned, "BANNED");

        assertThat(repository.countByTripIds(List.of(trip))).isEmpty();
        assertThat(repository.countActive(trip)).isZero();
    }

    @Test
    @DisplayName("findByUserIdOrderByCreatedAtDesc ne rend que les j'aime de l'utilisateur")
    void findByUserId_ne_rend_que_les_siens() {
        UUID me = newUser();
        UUID other = newUser();
        UUID mine = UUID.randomUUID();
        repository.insertIfAbsent(UUID.randomUUID(), me, mine);
        repository.insertIfAbsent(UUID.randomUUID(), other, UUID.randomUUID());
        entityManager.clear();

        assertThat(repository.findByUserIdOrderByCreatedAtDesc(me))
                .extracting(TripLikeEntity::getTripId).containsExactly(mine);
    }

    @Test
    @DisplayName("countByTripIds omet les trajets sans j'aime")
    void countByTripIds_omet_les_trajets_sans_j_aime() {
        UUID user = newUser();
        UUID liked = UUID.randomUUID();
        UUID unliked = UUID.randomUUID();
        repository.insertIfAbsent(UUID.randomUUID(), user, liked);

        List<Object[]> counts = repository.countByTripIds(List.of(liked, unliked));

        assertThat(counts).hasSize(1);
        assertThat(counts.get(0)[0]).isEqualTo(liked);
        assertThat(repository.countActive(unliked)).isZero();
    }

    @Test
    @DisplayName("findLikedTripIds ne rend que les j'aime du lecteur")
    void findLikedTripIds_ne_rend_que_ceux_du_lecteur() {
        UUID reader = newUser();
        UUID other = newUser();
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        repository.insertIfAbsent(UUID.randomUUID(), reader, mine);
        repository.insertIfAbsent(UUID.randomUUID(), other, theirs);

        assertThat(repository.findLikedTripIds(reader, List.of(mine, theirs, none))).containsExactly(mine);
    }
}

package com.yadony.api.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recherche admin des utilisateurs par identifiant, sur une vraie base PostgreSQL : la
 * requête est native et ses CAST de paramètres nuls ne se vérifient pas sous H2.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Recherche admin d'utilisateurs — UID Firebase et identifiant")
class UserRepositoryAdminSearchIT {

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
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private UserRepository userRepository;

    private UserEntity awa;
    private UserEntity moussa;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        awa = persist("FbUidAwa123XYZ", "Awa", "Diop");
        moussa = persist("FbUidMoussa456", "Moussa", "Traore");
    }

    @Test
    @DisplayName("UID Firebase exact → l'utilisateur est trouvé, total à 1")
    void exactFirebaseUid_matches() {
        Page<UserEntity> page = search("FbUidAwa123XYZ");

        assertThat(page.getContent()).extracting(UserEntity::getId).containsExactly(awa.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("UID Firebase partiel ou de casse différente → aucun résultat")
    void partialOrMiscasedFirebaseUid_doesNotMatch() {
        assertThat(search("FbUidAwa").getContent()).isEmpty();
        assertThat(search("fbuidawa123xyz").getContent()).isEmpty();
        assertThat(search("FbUidAwa").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("UUID de la base → l'utilisateur est trouvé, total à 1")
    void databaseId_matches() {
        Page<UserEntity> page = search(moussa.getId().toString());

        assertThat(page.getContent()).extracting(UserEntity::getId).containsExactly(moussa.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("UUID inconnu → aucun résultat")
    void unknownUuid_returnsEmpty() {
        Page<UserEntity> page = search(UUID.randomUUID().toString());

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("Texte quelconque → recherche partielle par nom inchangée")
    void freeText_stillMatchesNames() {
        Page<UserEntity> page = search("trao");

        assertThat(page.getContent()).extracting(UserEntity::getId).containsExactly(moussa.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sans terme → tous les utilisateurs, total cohérent")
    void noQuery_returnsAll() {
        Page<UserEntity> page = userRepository.findAdminFiltered(
                null, null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("Filtre testeurs recette → seuls les comptes au drapeau demandé")
    void recetteTesterFilter_matchesFlag() {
        awa.setRecetteTester(true);
        userRepository.save(awa);

        Page<UserEntity> testers = userRepository.findAdminFiltered(
                null, null, null, null, null, null, null, null, null, true, PageRequest.of(0, 20));
        Page<UserEntity> others = userRepository.findAdminFiltered(
                null, null, null, null, null, null, null, null, null, false, PageRequest.of(0, 20));

        assertThat(testers.getContent()).extracting(UserEntity::getId).containsExactly(awa.getId());
        assertThat(testers.getTotalElements()).isEqualTo(1);
        assertThat(others.getContent()).extracting(UserEntity::getId).containsExactly(moussa.getId());
    }

    private Page<UserEntity> search(String term) {
        UUID asId = asUuid(term);
        return userRepository.findAdminFiltered(
                null, null, null, null, "%" + term + "%", null, term, asId, null, null, PageRequest.of(0, 20));
    }

    private static UUID asUuid(String term) {
        try {
            return UUID.fromString(term);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private UserEntity persist(String firebaseUid, String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        u.setRoles(roles);
        return userRepository.save(u);
    }
}

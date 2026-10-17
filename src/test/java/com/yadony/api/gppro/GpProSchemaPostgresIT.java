package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Valide V326 (GP Pro) sur Postgres embarqué avec toutes les migrations Flyway et ddl-auto=validate. */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
@DisplayName("GP Pro - schéma V326")
class GpProSchemaPostgresIT {

    private static EmbeddedPostgres postgres;

    @Autowired UserRepository userRepository;
    @Autowired GpProApplicationRepository applicationRepository;
    @Autowired GpProDocumentRepository documentRepository;
    @Autowired GpProReviewFlagRepository flagRepository;
    @Autowired JdbcTemplate jdbcTemplate;

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

    @Test
    @DisplayName("la migration s'applique et les entités valident")
    void migrationAppliesAndEntitiesValidate() {
        assertThat(applicationRepository.countByStatus(GpProApplicationStatus.PENDING)).isZero();
        assertThat(documentRepository.findOrphansCreatedBefore(java.time.LocalDateTime.now())).isEmpty();
        assertThat(documentRepository.findUnpurgedOfDecidedApplications()).isEmpty();
        assertThat(flagRepository.countDistinctUserIdByResolvedAtIsNullAndDismissedAtIsNull()).isZero();
    }

    @Test
    @DisplayName("une seconde demande ouverte pour le même utilisateur est rejetée par l'index")
    void secondOpenApplicationForSameUserIsRejectedByIndex() {
        UserEntity user = persistUser(false);
        applicationRepository.saveAndFlush(application(user.getId(), GpProApplicationStatus.PENDING));

        assertThatThrownBy(() ->
                applicationRepository.saveAndFlush(application(user.getId(), GpProApplicationStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("une demande décidée n'empêche pas d'en ouvrir une autre, et decide() ne touche que PENDING")
    void decideOnlyUpdatesPending() {
        UserEntity user = persistUser(false);
        GpProApplicationEntity app = applicationRepository.saveAndFlush(
                application(user.getId(), GpProApplicationStatus.PENDING));
        UUID admin = UUID.randomUUID();

        int first = applicationRepository.decide(app.getId(), GpProApplicationStatus.REJECTED, admin, "x",
                java.time.LocalDateTime.now());
        int second = applicationRepository.decide(app.getId(), GpProApplicationStatus.APPROVED, admin, "y",
                java.time.LocalDateTime.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        applicationRepository.saveAndFlush(application(user.getId(), GpProApplicationStatus.PENDING));
        assertThat(applicationRepository.findFirstByUserIdAndStatusIn(user.getId(),
                Set.of(GpProApplicationStatus.PENDING))).isPresent();
    }

    @Test
    @DisplayName("les titulaires de kilo_pro ne sont pas GP Pro")
    void kiloProUsersAreNotGpPro() {
        UserEntity user = persistUser(true);

        Boolean gpPro = jdbcTemplate.queryForObject(
                "SELECT gp_pro FROM users WHERE id = ?", Boolean.class, user.getId());
        Boolean kiloPro = jdbcTemplate.queryForObject(
                "SELECT kilo_pro FROM users WHERE id = ?", Boolean.class, user.getId());

        assertThat(kiloPro).isTrue();
        assertThat(gpPro).isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isGpPro()).isFalse();
    }

    @Test
    @DisplayName("les requêtes de purge et de comptage distinguent bien les lignes avec données")
    void cleanupAndCountQueriesWithData() {
        UserEntity owner = persistUser(false);
        GpProApplicationEntity approved = applicationRepository.saveAndFlush(
                application(owner.getId(), GpProApplicationStatus.APPROVED));
        UserEntity other = persistUser(false);
        GpProApplicationEntity pending = applicationRepository.saveAndFlush(
                application(other.getId(), GpProApplicationStatus.PENDING));

        GpProDocumentEntity oldOrphan = document(owner.getId(), null);
        GpProDocumentEntity recentOrphan = document(owner.getId(), null);
        GpProDocumentEntity ofApproved = document(owner.getId(), approved.getId());
        GpProDocumentEntity ofPending = document(other.getId(), pending.getId());
        jdbcTemplate.update("UPDATE gp_pro_documents SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(30)), oldOrphan.getId());

        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(24);
        assertThat(documentRepository.findOrphansCreatedBefore(cutoff))
                .extracting(GpProDocumentEntity::getId)
                .contains(oldOrphan.getId())
                .doesNotContain(recentOrphan.getId(), ofApproved.getId(), ofPending.getId());
        assertThat(documentRepository.findUnpurgedOfDecidedApplications())
                .extracting(GpProDocumentEntity::getId)
                .contains(ofApproved.getId())
                .doesNotContain(ofPending.getId(), oldOrphan.getId(), recentOrphan.getId());

        long before = flagRepository.countDistinctUserIdByResolvedAtIsNullAndDismissedAtIsNull();
        flagRepository.saveAndFlush(flag(owner.getId(), GpProReviewReason.LOW_RATING));
        flagRepository.saveAndFlush(flag(owner.getId(), GpProReviewReason.OPEN_REPORT));
        assertThat(flagRepository.countDistinctUserIdByResolvedAtIsNullAndDismissedAtIsNull()).isEqualTo(before + 1);
        assertThat(flagRepository.findReviewUserIds(org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent()).contains(owner.getId());
    }

    private GpProDocumentEntity document(UUID ownerId, UUID applicationId) {
        GpProDocumentEntity d = new GpProDocumentEntity();
        d.setOwnerId(ownerId);
        d.setApplicationId(applicationId);
        d.setDocType(GpProDocumentType.TRAVEL_PROOF);
        d.setStorageKey("gp_pro/" + ownerId + "/" + UUID.randomUUID() + ".jpg");
        return documentRepository.saveAndFlush(d);
    }

    private GpProReviewFlagEntity flag(UUID userId, GpProReviewReason reason) {
        GpProReviewFlagEntity f = new GpProReviewFlagEntity();
        f.setUserId(userId);
        f.setReason(reason);
        f.setDetectedAt(java.time.LocalDateTime.now());
        return f;
    }

    private GpProApplicationEntity application(UUID userId, GpProApplicationStatus status) {
        GpProApplicationEntity a = new GpProApplicationEntity();
        a.setUserId(userId);
        a.setStatus(status);
        a.setSource(GpProApplicationSource.PROFILE);
        a.setTravelFrequency(GpProTravelFrequency.MONTHLY);
        return a;
    }

    private UserEntity persistUser(boolean kiloPro) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-gppro-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        u.setTotalTrips(0);
        u.setKiloPro(kiloPro);
        return userRepository.saveAndFlush(u);
    }
}

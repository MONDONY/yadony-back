package com.yadony.api.kyc.reminder;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sélection des comptes à relancer pour la vérification d'identité, sur Postgres embarqué. */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Relances KYC : sélection des candidats (Postgres)")
class KycReminderRepositoryPostgresIT {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final List<String> CODES = List.of("DOCUMENT_UNREADABLE", "DOCUMENT_EXPIRED");

    private static EmbeddedPostgres postgres;

    @Autowired KycReminderRepository repository;
    @Autowired JdbcTemplate jdbc;

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

    /** Compte non vérifié, session ouverte non soumise, ancre il y a {@code anchorDaysAgo} jours, aucune relance. */
    private UUID user(int anchorDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username, kyc_status, status, kyc_reminder_anchor_at)"
                        + " VALUES (?, ?, ?, 'PENDING', 'ACTIVE', ?)",
                id, "uid-" + id, "u" + id.toString().substring(0, 8), ts(NOW.minus(anchorDaysAgo, ChronoUnit.DAYS)));
        jdbc.update("INSERT INTO kyc_schema.kyc_verifications (user_id, verification_session_id, status)"
                + " VALUES (?, ?, 'PENDING')", id, "sess_" + id);
        return id;
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    private void userSet(UUID id, String sql, Object... args) {
        Object[] all = new Object[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = id;
        jdbc.update("UPDATE users SET " + sql + " WHERE id = ?", all);
    }

    private void kycSet(UUID id, String sql, Object... args) {
        Object[] all = new Object[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = id;
        jdbc.update("UPDATE kyc_schema.kyc_verifications SET " + sql + " WHERE user_id = ?", all);
    }

    private List<UUID> candidates() {
        return candidates(CODES);
    }

    private List<UUID> candidates(List<String> codes) {
        Instant d = NOW;
        return repository.findCandidates(d.minus(1, ChronoUnit.DAYS), d.minus(3, ChronoUnit.DAYS),
                d.minus(7, ChronoUnit.DAYS), d.minus(2, ChronoUnit.DAYS), d.minus(4, ChronoUnit.DAYS), codes);
    }

    @Test
    @DisplayName("abandon depuis plus d'un jour : candidat")
    void abandonDepuisPlusDUnJour_estCandidat() {
        UUID id = user(2);
        assertThat(candidates()).contains(id);
        assertThat(repository.isStillCandidate(id, CODES)).isEqualTo(1);
    }

    @Test
    @DisplayName("abandon depuis moins d'un jour : pas candidat")
    void abandonDepuisMoinsDUnJour_nonCandidat() {
        UUID id = user(0);
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("en examen (submitted_at posé) : pas candidat")
    void enExamen_nonCandidat() {
        UUID id = user(5);
        kycSet(id, "submitted_at = NOW()");
        assertThat(candidates()).doesNotContain(id);
        assertThat(repository.isStillCandidate(id, CODES)).isZero();
    }

    @Test
    @DisplayName("vérifié : pas candidat")
    void verifie_nonCandidat() {
        UUID id = user(5);
        userSet(id, "kyc_status = 'VERIFIED'");
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("désinscrit : pas candidat")
    void desinscrit_nonCandidat() {
        UUID id = user(5);
        userSet(id, "kyc_reminders_opted_out_at = NOW()");
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("refus d'un admin : pas candidat")
    void refusAdmin_nonCandidat() {
        UUID id = user(5);
        kycSet(id, "status = 'REJECTED', rejection_code = 'DOCUMENT_EXPIRED', decided_by_admin_id = ?", UUID.randomUUID());
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("révocation admin : pas candidat")
    void revocationAdmin_nonCandidat() {
        UUID id = user(5);
        kycSet(id, "decision_kind = 'REVOKED'");
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("reset admin sans session : pas candidat")
    void resetAdmin_sansSession_nonCandidat() {
        UUID id = user(5);
        kycSet(id, "verification_session_id = NULL");
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("banni, suspendu ou suppression demandée : pas candidats")
    void banni_suspendu_suppressionDemandee_nonCandidats() {
        UUID banni = user(5);
        UUID suspendu = user(5);
        UUID suppression = user(5);
        userSet(banni, "status = 'BANNED'");
        userSet(suspendu, "status = 'SUSPENDED'");
        userSet(suppression, "deletion_requested_at = NOW()");
        assertThat(candidates()).doesNotContain(banni, suspendu, suppression);
    }

    @Test
    @DisplayName("refus avec code relançable (casse ignorée) : candidat")
    void refusCodeRelancable_estCandidat() {
        UUID id = user(5);
        kycSet(id, "status = 'REJECTED', rejection_code = 'document_expired'");
        assertThat(candidates()).contains(id);
        assertThat(repository.isStillCandidate(id, CODES)).isEqualTo(1);
        assertThat(repository.reminderCase(id)).isEqualTo("rejected");
    }

    @Test
    @DisplayName("refus avec code inconnu : pas candidat")
    void refusCodeInconnu_nonCandidat() {
        UUID id = user(5);
        kycSet(id, "status = 'REJECTED', rejection_code = 'verification_failed'");
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("refus pour l'âge : pas candidat")
    void refusAge_nonCandidat() {
        UUID id = user(5);
        kycSet(id, "status = 'REJECTED', rejection_code = 'MINIMUM_AGE'");
        assertThat(candidates()).doesNotContain(id);
    }

    @Test
    @DisplayName("liste de codes vide : les refus ne sont jamais relancés, les abandons le sont")
    void listeVide_aucunRefus() {
        UUID refuse = user(5);
        kycSet(refuse, "status = 'REJECTED', rejection_code = 'DOCUMENT_EXPIRED'");
        UUID abandon = user(5);

        assertThat(candidates(List.of())).contains(abandon).doesNotContain(refuse);
        assertThat(repository.isStillCandidate(refuse, List.of())).isZero();
        assertThat(repository.isStillCandidate(abandon, List.of())).isEqualTo(1);
    }

    @Test
    @DisplayName("2e relance : attend un écart de deux jours")
    void deuxiemeRelance_attendEcartDeDeuxJours() {
        UUID trop = user(10);
        userSet(trop, "kyc_reminder_count = 1, kyc_reminder_last_at = ?", ts(NOW.minus(1, ChronoUnit.DAYS)));
        UUID assez = user(10);
        userSet(assez, "kyc_reminder_count = 1, kyc_reminder_last_at = ?", ts(NOW.minus(2, ChronoUnit.DAYS)));
        assertThat(candidates()).contains(assez).doesNotContain(trop);
    }

    @Test
    @DisplayName("3e relance : attend un écart de quatre jours")
    void troisiemeRelance_attendEcartDeQuatreJours() {
        UUID trop = user(10);
        userSet(trop, "kyc_reminder_count = 2, kyc_reminder_last_at = ?", ts(NOW.minus(3, ChronoUnit.DAYS)));
        UUID assez = user(10);
        userSet(assez, "kyc_reminder_count = 2, kyc_reminder_last_at = ?", ts(NOW.minus(4, ChronoUnit.DAYS)));
        assertThat(candidates()).contains(assez).doesNotContain(trop);
    }

    @Test
    @DisplayName("après trois relances : plus rien")
    void apresTroisRelances_plusRien() {
        UUID id = user(30);
        userSet(id, "kyc_reminder_count = 3, kyc_reminder_last_at = ?", ts(NOW.minus(10, ChronoUnit.DAYS)));
        assertThat(candidates()).doesNotContain(id);
        assertThat(repository.isStillCandidate(id, CODES)).isZero();
    }

    @Test
    @DisplayName("reminderCase : rejected si REJECTED, sinon incomplete")
    void reminderCase_rejectedOuIncomplete() {
        UUID incomplet = user(5);
        UUID refuse = user(5);
        kycSet(refuse, "status = 'REJECTED'");
        assertThat(repository.reminderCase(incomplet)).isEqualTo("incomplete");
        assertThat(repository.reminderCase(refuse)).isEqualTo("rejected");
    }
}

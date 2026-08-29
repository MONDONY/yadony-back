package com.yadony.api.migrations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logique métier du backfill de la migration V231 : chaque compte PRO existant
 * reçoit une grâce de 60 jours.
 *
 * <p><b>Stratégie</b> — identique à {@link V89MigrationTest} : le profil de test
 * tourne sur H2 avec Flyway désactivé, le fichier de migration n'est donc jamais
 * exécuté. On rejoue ici le même SELECT avec les fonctions H2 équivalentes
 * ({@code DATEADD} pour {@code NOW() + INTERVAL}, {@code RANDOM_UUID()} pour
 * {@code gen_random_uuid()}). Le SQL de production cible PostgreSQL 16.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("V231 — backfill des comptes PRO existants en LEGACY_GRACE")
class V231MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID proUserId = UUID.randomUUID();
    private final UUID plainUserId = UUID.randomUUID();
    private final UUID deletedProUserId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pro_subscriptions");
        // Reprend la liste de colonnes de l'INSERT de V89MigrationTest (tenue à jour avec
        // les colonnes NOT NULL de `users`), en y ajoutant is_pro_account (déjà paramétrée
        // ici plutôt que figée à false) et deleted_at. Une colonne NOT NULL absente de cet
        // INSERT fait échouer le test sous H2.
        insertUser(proUserId, "userv231one", true, null);
        insertUser(plainUserId, "userv231two", false, null);
        insertUser(deletedProUserId, "userv231three", true, "2026-01-01 00:00:00");
    }

    private void insertUser(UUID id, String username, boolean pro, String deletedAt) {
        Timestamp deletedAtTimestamp = deletedAt == null ? null : Timestamp.valueOf(deletedAt);
        jdbc.update(
                "INSERT INTO users (id, firebase_uid, username, status, kyc_status, stripe_account_status, " +
                "cancellation_count, is_pro_account, contact_kyc_only, hide_phone_number, country, " +
                "kilo_pro, total_trips, total_shipments, no_show_count, refused_count, rating_count, " +
                "version, deleted_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', 'PENDING', 'NOT_CREATED', 0, ?, true, false, 'FR', " +
                "false, 0, 0, 0, 0, 0, 0, ?, NOW(), NOW())",
                id, "uid-" + id, username, pro, deletedAtTimestamp);
    }

    /**
     * Équivalent H2 du backfill de V231__pro_subscriptions.sql.
     *
     * <p>Écart avec le brief : {@code updated_at} est ajouté à la liste de colonnes. Le
     * schéma H2 de ce test vient des entités JPA (Flyway désactivé) et {@link
     * com.yadony.api.common.BaseEntity#getUpdatedAt()} est {@code nullable = false} ; la
     * migration SQL de production, elle, déclare {@code updated_at} nullable et n'a pas
     * ce problème sous PostgreSQL.
     */
    private void runBackfill() {
        jdbc.update("INSERT INTO pro_subscriptions "
                + "(id, user_id, status, source, grace_expires_at, cancel_at_period_end, created_at, updated_at) "
                + "SELECT RANDOM_UUID(), id, 'LEGACY_GRACE', 'LEGACY_FREE', "
                + "DATEADD('DAY', 60, NOW()), FALSE, NOW(), NOW() "
                + "FROM users WHERE is_pro_account = TRUE AND deleted_at IS NULL");
    }

    @Test
    @DisplayName("un compte PRO actif reçoit une grâce de 60 jours")
    void proUserGetsGrace() {
        runBackfill();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pro_subscriptions WHERE user_id = ?", Integer.class, proUserId);
        assertThat(count).isEqualTo(1);

        String status = jdbc.queryForObject(
                "SELECT status FROM pro_subscriptions WHERE user_id = ?", String.class, proUserId);
        assertThat(status).isEqualTo("LEGACY_GRACE");

        String source = jdbc.queryForObject(
                "SELECT source FROM pro_subscriptions WHERE user_id = ?", String.class, proUserId);
        assertThat(source).isEqualTo("LEGACY_FREE");

        Integer daysAhead = jdbc.queryForObject(
                "SELECT DATEDIFF('DAY', NOW(), grace_expires_at) FROM pro_subscriptions WHERE user_id = ?",
                Integer.class, proUserId);
        assertThat(daysAhead).isBetween(59, 60);
    }

    @Test
    @DisplayName("un compte non PRO ne reçoit rien")
    void plainUserGetsNothing() {
        runBackfill();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pro_subscriptions WHERE user_id = ?", Integer.class, plainUserId);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("un compte PRO supprimé est ignoré")
    void softDeletedProUserIsSkipped() {
        runBackfill();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pro_subscriptions WHERE user_id = ?",
                Integer.class, deletedProUserId);
        assertThat(count)
                .as("le backfill filtre sur deleted_at IS NULL")
                .isZero();
    }

    @Test
    @DisplayName("le backfill crée exactement une ligne par compte PRO éligible")
    void oneRowPerEligibleUser() {
        runBackfill();

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM pro_subscriptions", Integer.class);
        assertThat(total).isEqualTo(1);
    }
}

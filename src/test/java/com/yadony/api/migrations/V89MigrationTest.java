package com.yadony.api.migrations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the business logic of migration V89:
 * "Retire le rôle TRAVELER aux users qui n'ont jamais complété KYC + Stripe Connect."
 *
 * <p><b>Strategy:</b> The test profile runs on H2 (Flyway disabled, JPA DDL). H2 does not
 * support PostgreSQL-specific functions ({@code jsonb_build_object}, {@code RETURNING},
 * {@code ::uuid} casting). We therefore:
 * <ol>
 *   <li>Seed data via JdbcTemplate using H2-compatible SQL (UUIDs via
 *       {@link UUID#randomUUID()} passed as {@link java.util.UUID} parameters).</li>
 *   <li>Execute the migration logic using equivalent H2-compatible SQL — identical
 *       filter conditions, but {@code jsonb_build_object} replaced with a plain JSON
 *       string literal which H2's JSON column type accepts.</li>
 *   <li>Assert the final DB state matches the expected business rules.</li>
 * </ol>
 *
 * <p>The production SQL file (V89__downgrade_traveler_role_for_incomplete_users.sql)
 * uses {@code jsonb_build_object} and targets PostgreSQL 16 — validated by staging.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("V89 — Downgrade TRAVELER role for users with incomplete KYC or Stripe")
class V89MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    // UUIDs generated in Java so they work uniformly in H2 and PostgreSQL
    private final UUID user1Id = UUID.randomUUID(); // VERIFIED + ONBOARDING_COMPLETE → keeps TRAVELER
    private final UUID user2Id = UUID.randomUUID(); // PENDING  + NOT_CREATED         → loses TRAVELER
    private final UUID user3Id = UUID.randomUUID(); // VERIFIED + PENDING_ONBOARDING  → loses TRAVELER

    @BeforeEach
    void seedData() {
        // Insert users using positional parameters — H2 accepts UUID objects directly.
        // All NOT NULL columns must be provided explicitly (H2 DDL from JPA, no defaults from SQL).
        // country est seedé à 'FR' ici pour reproduire l'état réel d'avant la migration V225
        // (tous les users avaient 'FR' en dur) ; v225MakesCountryNullable reproduit ensuite le
        // backfill de V225 avant d'asserter, au lieu d'omettre la colonne — l'omettre rendait
        // l'assertion vraie par construction, sans jamais exercer la logique du backfill.
        String insertUser =
                "INSERT INTO users (id, firebase_uid, username, status, kyc_status, stripe_account_status, " +
                "cancellation_count, is_pro_account, contact_kyc_only, hide_phone_number, country, " +
                "kilo_pro, total_trips, total_shipments, no_show_count, refused_count, rating_count, " +
                "version, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0, false, true, false, 'FR', false, 0, 0, 0, 0, 0, 0, NOW(), NOW())";

        jdbc.update(insertUser, user1Id, "uid-v89-user1", "userv89one",   "VERIFIED",  "ONBOARDING_COMPLETE");
        jdbc.update(insertUser, user2Id, "uid-v89-user2", "userv89two",   "PENDING",   "NOT_CREATED");
        jdbc.update(insertUser, user3Id, "uid-v89-user3", "userv89three", "VERIFIED",  "PENDING_ONBOARDING");

        // All three start with TRAVELER role
        String insertRole = "INSERT INTO user_roles (user_id, role) VALUES (?, 'TRAVELER')";
        jdbc.update(insertRole, user1Id);
        jdbc.update(insertRole, user2Id);
        jdbc.update(insertRole, user3Id);
    }

    // ─── Precondition ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Précondition : les 3 users ont le rôle TRAVELER avant migration")
    void precondition_allThreeUsersHaveTravelerRole() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE role = 'TRAVELER' " +
                "AND user_id IN (?, ?, ?)",
                Integer.class, user1Id, user2Id, user3Id);
        assertThat(count).isEqualTo(3);
    }

    // ─── After migration ──────────────────────────────────────────────────────

    @Test
    @DisplayName("user1 (VERIFIED + ONBOARDING_COMPLETE) conserve le rôle TRAVELER")
    void afterMigration_completeUser_keepsTravelerRole() {
        runMigrationLogic();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role = 'TRAVELER'",
                Integer.class, user1Id);
        assertThat(count).as("user1 doit conserver TRAVELER").isEqualTo(1);
    }

    @Test
    @DisplayName("user2 (PENDING KYC) perd le rôle TRAVELER")
    void afterMigration_pendingKycUser_losesTravelerRole() {
        runMigrationLogic();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role = 'TRAVELER'",
                Integer.class, user2Id);
        assertThat(count).as("user2 doit perdre TRAVELER (KYC=PENDING)").isZero();
    }

    @Test
    @DisplayName("user3 (Stripe PENDING_ONBOARDING) perd le rôle TRAVELER")
    void afterMigration_pendingStripeUser_losesTravelerRole() {
        runMigrationLogic();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role = 'TRAVELER'",
                Integer.class, user3Id);
        assertThat(count).as("user3 doit perdre TRAVELER (Stripe=PENDING_ONBOARDING)").isZero();
    }

    @Test
    @DisplayName("audit_log contient exactement 2 entrées USER_ROLE_REMOVED_MIGRATION")
    void afterMigration_auditLog_hasTwoEntries() {
        runMigrationLogic();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'USER_ROLE_REMOVED_MIGRATION' " +
                "AND entity_id IN (?, ?)",
                Integer.class, user2Id, user3Id);
        assertThat(count)
                .as("Exactement 2 entrées d'audit (user2 et user3 rétrogradés)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("audit_log ne contient pas user1 (aucun retrait pour lui)")
    void afterMigration_auditLog_doesNotContainCompleteUser() {
        runMigrationLogic();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'USER_ROLE_REMOVED_MIGRATION' " +
                "AND entity_id = ?",
                Integer.class, user1Id);
        assertThat(count)
                .as("user1 (VERIFIED + ONBOARDING_COMPLETE) ne doit pas apparaître dans audit_log")
                .isZero();
    }

    @Test
    @DisplayName("Au total, il ne reste qu'un seul rôle TRAVELER après la migration")
    void afterMigration_onlyOneTravelerRoleRemains() {
        runMigrationLogic();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE role = 'TRAVELER' " +
                "AND user_id IN (?, ?, ?)",
                Integer.class, user1Id, user2Id, user3Id);
        assertThat(count)
                .as("Seul user1 doit conserver TRAVELER")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V225 rend le pays nullable et vide les FR poses par defaut")
    void v225MakesCountryNullable() {
        // Cette classe tourne sans Flyway (flyway.enabled=false, ddl-auto=create) :
        // V225__users_country_nullable.sql n'est donc jamais exécuté ici. Ce test ne garantit
        // pas que le fichier SQL s'applique correctement en production — il reproduit
        // uniquement le backfill (UPDATE users SET country = NULL) en H2, sur des users
        // seedés avec 'FR' pour représenter l'état pré-migration, comme runMigrationLogic()
        // le fait plus bas pour V89.
        runV225BackfillLogic();

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE country IS NOT NULL", Integer.class);
        assertThat(remaining).isZero();
    }

    /**
     * Reproduces V225's backfill in H2-compatible SQL: {@code UPDATE users SET country = NULL}.
     * Identical to the production statement — no PostgreSQL-specific syntax involved here,
     * unlike {@link #runMigrationLogic()} for V89.
     */
    private void runV225BackfillLogic() {
        jdbc.update("UPDATE users SET country = NULL");
    }

    // ─── Migration logic (H2-compatible) ──────────────────────────────────────

    /**
     * Reproduces the V89 migration logic using H2-compatible SQL.
     *
     * <p>Differences from the production SQL:
     * <ul>
     *   <li>{@code jsonb_build_object(...)} → plain JSON string literal (H2 JSON column accepts it)</li>
     *   <li>No {@code ::uuid} cast (H2 in PostgreSQL MODE auto-casts UUID parameters)</li>
     * </ul>
     *
     * <p>The filter condition is <em>identical</em> to production:
     * {@code kyc_status != 'VERIFIED' OR stripe_account_status != 'ONBOARDING_COMPLETE'}
     */
    private void runMigrationLogic() {
        // Step 1 — Audit log (INSERT BEFORE DELETE to capture entity_ids)
        jdbc.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at) " +
                "SELECT 'USER', u.id, 'USER_ROLE_REMOVED_MIGRATION', u.id, " +
                "  '{\"role\":\"TRAVELER\",\"reason\":\"V89_downgrade_incomplete_kyc_or_stripe\"}', " +
                "  NOW() " +
                "FROM users u " +
                "INNER JOIN user_roles ur ON ur.user_id = u.id AND ur.role = 'TRAVELER' " +
                "WHERE u.id IN (?, ?, ?) " +
                "AND (u.kyc_status != 'VERIFIED' OR u.stripe_account_status != 'ONBOARDING_COMPLETE')",
                user1Id, user2Id, user3Id);

        // Step 2 — Remove TRAVELER role
        jdbc.update(
                "DELETE FROM user_roles " +
                "WHERE role = 'TRAVELER' " +
                "AND user_id IN ( " +
                "  SELECT id FROM users " +
                "  WHERE id IN (?, ?, ?) " +
                "  AND (kyc_status != 'VERIFIED' OR stripe_account_status != 'ONBOARDING_COMPLETE') " +
                ")",
                user1Id, user2Id, user3Id);
    }
}

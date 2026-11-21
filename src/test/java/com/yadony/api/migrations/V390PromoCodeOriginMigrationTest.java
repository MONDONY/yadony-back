package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V390 : origine des codes promo (ADMIN, REFERRAL_REWARD, REFERRAL_WELCOME) et lien d'une
 * invitation vers le code de bienvenue du filleul.
 */
class V390PromoCodeOriginMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static UUID ownedPromo;
    private static UUID globalPromo;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway before = flywayUpTo("389");
        before.clean();
        before.migrate();

        UUID owner = user();
        ownedPromo = UUID.randomUUID();
        globalPromo = UUID.randomUUID();
        jdbc.update("INSERT INTO promo_codes (id, code, rate, owner_user_id, created_at) "
                + "VALUES (?, 'MERCI-ABOU-7K2F', 0.050, ?, now())", ownedPromo, owner);
        jdbc.update("INSERT INTO promo_codes (id, code, rate, created_at) VALUES (?, 'WELCOME05', 0.050, now())",
                globalPromo);

        flywayUpTo("390").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    private static UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', now(), now())", id, "uid-" + id, "u-" + id.toString().substring(0, 8));
        return id;
    }

    private String origin(UUID promoId) {
        return jdbc.queryForObject("SELECT origin FROM promo_codes WHERE id = ?", String.class, promoId);
    }

    @Test
    void origin_backfilled_from_owner() {
        assertThat(origin(ownedPromo)).isEqualTo("REFERRAL_REWARD");
        assertThat(origin(globalPromo)).isEqualTo("ADMIN");
    }

    @Test
    void origin_isNotNullAndDefaultsToAdmin() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO promo_codes (id, code, rate, created_at) VALUES (?, 'DEFAULTED', 0.050, now())", id);
        assertThat(origin(id)).isEqualTo("ADMIN");
        assertThatThrownBy(() -> jdbc.update("UPDATE promo_codes SET origin = NULL WHERE id = ?", globalPromo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void origin_checkRejectsUnknownValue() {
        assertThatThrownBy(() -> jdbc.update("UPDATE promo_codes SET origin = 'BOGUS' WHERE id = ?", globalPromo))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE promo_codes SET origin = 'REFERRAL_WELCOME' WHERE id = ?", globalPromo);
        assertThat(origin(globalPromo)).isEqualTo("REFERRAL_WELCOME");
        jdbc.update("UPDATE promo_codes SET origin = 'ADMIN' WHERE id = ?", globalPromo);
    }

    @Test
    void welcomePromoCodeId_isNullableWithForeignKey() {
        UUID referrer = user();
        UUID referee = user();
        UUID inv = UUID.randomUUID();
        jdbc.update("INSERT INTO referral_invitations (id, referrer_user_id, referee_user_id, status) "
                + "VALUES (?, ?, ?, 'SIGNED_UP')", inv, referrer, referee);
        assertThat(jdbc.queryForObject("SELECT welcome_promo_code_id FROM referral_invitations WHERE id = ?",
                UUID.class, inv)).isNull();

        jdbc.update("UPDATE referral_invitations SET welcome_promo_code_id = ? WHERE id = ?", globalPromo, inv);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE referral_invitations SET welcome_promo_code_id = ? WHERE id = ?", UUID.randomUUID(), inv))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

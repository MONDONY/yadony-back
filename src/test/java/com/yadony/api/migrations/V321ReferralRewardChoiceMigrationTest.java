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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V321 : choix de la récompense de parrainage. Les invitations déjà REWARDED (bon -50 %
 * octroyé d'office) passent en CHOSEN / COMMISSION_VOUCHER et ne redeviennent jamais
 * choisissables ; les autres restent NONE. Les codes promo existants restent globaux et
 * en points de commission.
 */
class V321ReferralRewardChoiceMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static UUID referrer;
    private static UUID rewarded;
    private static UUID signedUp;
    private static UUID promo;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway before = flywayUpTo("319");
        before.clean();
        before.migrate();

        referrer = user();
        UUID refereeA = user();
        UUID refereeB = user();
        rewarded = UUID.randomUUID();
        signedUp = UUID.randomUUID();
        jdbc.update("INSERT INTO referral_invitations (id, referrer_user_id, referee_user_id, status, rewarded_at) "
                + "VALUES (?, ?, ?, 'REWARDED', now() - interval '10 days')", rewarded, referrer, refereeA);
        jdbc.update("INSERT INTO referral_invitations (id, referrer_user_id, referee_user_id, status) "
                + "VALUES (?, ?, ?, 'SIGNED_UP')", signedUp, referrer, refereeB);
        promo = UUID.randomUUID();
        jdbc.update("INSERT INTO promo_codes (id, code, rate, created_at) VALUES (?, 'WELCOME05', 0.050, now())", promo);

        flywayUpTo("321").migrate();
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

    @Test
    void rewardedInvitations_becomeChosenHistoricalVouchers() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reward_status, reward_choice, reward_available_at, reward_chosen_at, rewarded_at "
                        + "FROM referral_invitations WHERE id = ?", rewarded);
        assertThat(row.get("reward_status")).isEqualTo("CHOSEN");
        assertThat(row.get("reward_choice")).isEqualTo("COMMISSION_VOUCHER");
        assertThat(row.get("reward_chosen_at")).isEqualTo(row.get("rewarded_at"));
        assertThat(row.get("reward_available_at")).isEqualTo(row.get("rewarded_at"));
    }

    @Test
    void otherInvitations_stayWithoutReward() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reward_status, reward_choice, reward_promo_code_id FROM referral_invitations WHERE id = ?",
                signedUp);
        assertThat(row.get("reward_status")).isEqualTo("NONE");
        assertThat(row.get("reward_choice")).isNull();
        assertThat(row.get("reward_promo_code_id")).isNull();
    }

    @Test
    void existingPromoCodes_stayGlobalCommissionPoints() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT owner_user_id, discount_kind FROM promo_codes WHERE id = ?", promo);
        assertThat(row.get("owner_user_id")).isNull();
        assertThat(row.get("discount_kind")).isEqualTo("COMMISSION_POINTS");
    }

    @Test
    void choiceMustMatchStatus() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE referral_invitations SET reward_status = 'CHOSEN' WHERE id = ?", signedUp))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE referral_invitations SET reward_status = 'AVAILABLE', reward_choice = 'WALLET_CREDIT' "
                        + "WHERE id = ?", signedUp))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE promo_codes SET discount_kind = 'BOGUS' WHERE id = ?", promo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void promoCodeLinkOnlyForDiscountChoice() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE referral_invitations SET reward_promo_code_id = ? WHERE id = ?", promo, rewarded))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

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

/** V312 : versements carte réservés (PROD_CHECKLIST 9.23), une ligne par paiement, contraintes. */
class V312StripeReleaseIntentsMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("312").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        // Une seule connexion : les clés étrangères de payments (colis, utilisateurs) sont hors sujet ici,
        // désactivées pour les seules insertions de paiements de test.
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                dataSource.getConnection(), true));
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static UUID payment() {
        UUID bidId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.execute("SET session_replication_role = replica");
        jdbc.update("INSERT INTO payments (id, bid_id, amount, commission_amount, currency, status, created_at, updated_at) "
                + "VALUES (?, ?, 50.00, 6.00, 'EUR', 'RELEASED', now(), now())", id, bidId);
        jdbc.execute("SET session_replication_role = DEFAULT");
        return id;
    }

    private static void insert(UUID paymentId, String kind, String status, Long amount, String destination) {
        jdbc.update("INSERT INTO stripe_release_intents (payment_id, kind, status, amount_minor, currency, destination, "
                        + "source, audit_action, lease_until) VALUES (?, ?, ?, ?, 'eur', ?, 'delivery', 'X', now())",
                paymentId, kind, status, amount, destination);
    }

    @Test
    void v312_uneReservationParPaiement() {
        UUID p = payment();
        insert(p, "TRANSFER", "PENDING", 4400L, "acct_1");
        assertThatThrownBy(() -> insert(p, "TRANSFER", "PENDING", 4400L, "acct_1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v312_transferExigeMontantEtDestination() {
        assertThatThrownBy(() -> insert(payment(), "TRANSFER", "PENDING", null, "acct_1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(payment(), "TRANSFER", "PENDING", 4400L, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // La capture legacy n'a ni montant ni destination.
        insert(payment(), "LEGACY_CAPTURE", "PENDING", null, null);
    }

    @Test
    void v312_statutsEtTypesBornes() {
        assertThatThrownBy(() -> insert(payment(), "REFUND", "PENDING", 4400L, "acct_1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(payment(), "TRANSFER", "LOST", 4400L, "acct_1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v312_paiementInexistantRefuse() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "TRANSFER", "PENDING", 4400L, "acct_1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v312_derniereVersionAppliquee() {
        Integer max = jdbc.queryForObject(
                "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(max).isEqualTo(312);
    }
}

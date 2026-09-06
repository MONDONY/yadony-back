package com.yadony.api.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V250 — retrait des trois colonnes de confort {@code payments.pawapay_{deposit,payout,refund}_id}
 * posées par V248 : elles dupliquaient {@code pawapay_operations.payment_id} (le lien qui fait
 * autorité) et n'avaient qu'un lecteur. Le profil "test" tourne sur H2 avec Flyway désactivé :
 * on migre un PostgreSQL embarqué (zonky, même dépendance que les autres {@code V*MigrationTest})
 * jusqu'à V249 pour constater les colonnes, puis V250 pour constater leur retrait — le reste de
 * la table (dont {@code rail}, V248) doit rester intact.
 */
class V250PaymentsDropPawapayRefsMigrationTest {

    private static final List<String> DROPPED = List.of("pawapay_deposit_id", "pawapay_payout_id", "pawapay_refund_id");

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private static List<String> paymentColumns() throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'payments'")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    @Test
    void v250_dropsTheThreePawapayReferenceColumns_andKeepsTheRail() throws Exception {
        Flyway upTo249 = flywayUpTo("249");
        upTo249.clean();
        upTo249.migrate();
        assertThat(paymentColumns()).as("V248 les avait posées").containsAll(DROPPED).contains("rail");

        flywayUpTo("250").migrate();

        List<String> after = paymentColumns();
        assertThat(after).doesNotContainAnyElementsOf(DROPPED);
        assertThat(after).contains("rail", "captured_at", "escrow_released_at");
    }
}

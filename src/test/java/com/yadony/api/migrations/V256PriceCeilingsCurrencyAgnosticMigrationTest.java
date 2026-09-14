package com.yadony.api.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V256 : les plafonds SQL des prix de colis et de négociation ne sont plus « 500 euros ».
 *
 * <p>Les colonnes {@code target_price_eur}, {@code current_price_eur} et
 * {@code proposed_price_eur} portent depuis le passage multidevise le montant dans la devise
 * de la demande. Un budget de 3 000 F CFA (2 857,14 net) violait
 * {@code chk_pkg_req_target_price} (V57), et toute négociation en XOF au-dessus de 500 F CFA
 * aurait violé {@code chk_neg_thread_price} (V58) ou {@code chk_neg_msg_price} (V59). La borne
 * par devise vit dans {@code CurrencyBounds} ; la base ne garde qu'un garde-fou large.
 *
 * <p>Même méthode que V254 : PostgreSQL embarqué migré jusqu'à V255 puis V256, et lecture de
 * la définition des contraintes via {@code pg_get_constraintdef} plutôt qu'une insertion qui
 * traînerait toutes les clés étrangères.
 */
class V256PriceCeilingsCurrencyAgnosticMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    private static String constraintDefinition(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = '" + name + "'");
            assertThat(rs.next()).as("la contrainte %s doit exister", name).isTrue();
            return rs.getString(1);
        }
    }

    /** Un budget XOF réel (3 000 F CFA nets de commission) doit passer la contrainte. */
    private static boolean checkAccepts(String name, String table, String column, String value)
            throws Exception {
        String definition = constraintDefinition(name);
        String expression = definition.substring("CHECK ".length());
        String sql = "SELECT " + expression.replace(column, value);
        assertThat(sql).as("la contrainte %s porte bien sur %s", name, column)
                .isNotEqualTo("SELECT " + expression);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery(sql);
            assertThat(rs.next()).isTrue();
            return rs.getBoolean(1);
        }
    }

    @Test
    void v256_liftsTheEuroCeilings_andKeepsTheSignGuards() throws Exception {
        Flyway upTo255 = flywayUpTo("255");
        upTo255.clean();
        upTo255.migrate();

        assertThat(checkAccepts("chk_pkg_req_target_price", "package_requests",
                "target_price_eur", "2857.14::numeric"))
                .as("avant V256, un budget XOF de 2 857,14 est refusé").isFalse();
        assertThat(checkAccepts("chk_neg_thread_price", "negotiation_threads",
                "current_price_eur", "120000::numeric"))
                .as("avant V256, une négociation à 120 000 F CFA est refusée").isFalse();
        assertThat(checkAccepts("chk_neg_msg_price", "negotiation_messages",
                "proposed_price_eur", "120000::numeric"))
                .as("avant V256, une proposition à 120 000 F CFA est refusée").isFalse();

        flywayUpTo("256").migrate();

        assertThat(checkAccepts("chk_pkg_req_target_price", "package_requests",
                "target_price_eur", "2857.14::numeric")).isTrue();
        assertThat(checkAccepts("chk_pkg_req_target_price", "package_requests",
                "target_price_eur", "367336::numeric"))
                .as("le budget XOF maximal de CurrencyBounds (560 EUR) passe").isTrue();
        assertThat(checkAccepts("chk_pkg_req_target_price", "package_requests",
                "target_price_eur", "NULL::numeric"))
                .as("le budget reste optionnel").isTrue();
        assertThat(checkAccepts("chk_pkg_req_target_price", "package_requests",
                "target_price_eur", "-1::numeric"))
                .as("un budget négatif reste refusé").isFalse();

        assertThat(checkAccepts("chk_neg_thread_price", "negotiation_threads",
                "current_price_eur", "120000::numeric")).isTrue();
        assertThat(checkAccepts("chk_neg_thread_price", "negotiation_threads",
                "current_price_eur", "0::numeric"))
                .as("un prix de négociation nul reste refusé").isFalse();

        assertThat(checkAccepts("chk_neg_msg_price", "negotiation_messages",
                "proposed_price_eur", "120000::numeric")).isTrue();
        assertThat(checkAccepts("chk_neg_msg_price", "negotiation_messages",
                "proposed_price_eur", "NULL::numeric"))
                .as("ACCEPT et REJECT n'ont pas de prix").isTrue();
        assertThat(checkAccepts("chk_neg_msg_price", "negotiation_messages",
                "proposed_price_eur", "0::numeric")).isFalse();

        assertThat(checkAccepts("chk_pkg_req_target_price", "package_requests",
                "target_price_eur", "1000001::numeric"))
                .as("le garde-fou large reste borné, comme le DTO").isFalse();
    }
}

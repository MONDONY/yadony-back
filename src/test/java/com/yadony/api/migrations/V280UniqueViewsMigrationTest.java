package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V280 : une ligne par personne qui a vu un trajet ({@code announcement_views}) ou une
 * demande ({@code package_request_views}). La contrainte unique (objet, personne) est ce
 * qui garantit qu'une personne n'est comptée qu'une fois, même sur deux ouvertures
 * simultanées : sans elle, le décompte redevient un compteur d'ouvertures.
 */
class V280UniqueViewsMigrationTest {

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

    /** Contraintes de la table, sous la forme « nom:type » (p clé primaire, u unique, f clé étrangère). */
    private static List<String> constraints(String table) throws Exception {
        List<String> found = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT conname, contype FROM pg_constraint "
                    + "WHERE conrelid = to_regclass('public." + table + "') ORDER BY conname");
            while (rs.next()) found.add(rs.getString(1) + ":" + rs.getString(2));
        }
        return found;
    }

    private static String uniqueColumns(String constraint) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = '" + constraint + "'");
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void v280_creeLesTablesDeVues_avecUneLigneParPersonne() throws Exception {
        Flyway baseline = flywayUpTo("279");
        baseline.clean();
        baseline.migrate();
        assertThat(constraints("announcement_views")).isEmpty();

        flywayUpTo("280").migrate();

        assertThat(constraints("announcement_views"))
                .contains("uq_announcement_views:u")
                .filteredOn(name -> name.endsWith(":f")).hasSize(2);
        assertThat(uniqueColumns("uq_announcement_views")).isEqualTo("UNIQUE (announcement_id, viewer_id)");

        assertThat(constraints("package_request_views"))
                .contains("uq_package_request_views:u")
                .filteredOn(name -> name.endsWith(":f")).hasSize(2);
        assertThat(uniqueColumns("uq_package_request_views")).isEqualTo("UNIQUE (package_request_id, viewer_id)");
    }
}

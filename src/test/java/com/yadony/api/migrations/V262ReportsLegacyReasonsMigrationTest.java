package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V262 : rattrapage des signalements créés avant la PR #206, quand {@code reports.reason}
 * était un texte libre saisi depuis l'app. Depuis, l'entité mappe la colonne sur l'enum
 * {@code ReportReason} et une seule ligne héritée fait tomber toute la liste admin en 500
 * (« No enum constant ReportReason.Problème avec un utilisateur »).
 *
 * <p>Le libellé d'origine est conservé en tête de la description, et un CHECK interdit
 * qu'une valeur hors catalogue revienne.
 */
class V262ReportsLegacyReasonsMigrationTest {

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

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("261");
        baseline.clean();
        baseline.migrate();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void v262_mappeLesAnciensLibellesVersLeCatalogueEtGardeLeTexteDOrigine() throws Exception {
        UUID paiement = seed("Problème de paiement", "APP", "Le virement n'arrive pas");
        UUID bug = seed("Bug de l'application", "USER", null);
        UUID autre = seed("Autre", "APP", "Rien à voir");
        UUID utilisateur = seed("Problème avec un utilisateur", "USER", "Il ne répond plus");
        UUID colis = seed("Problème avec un colis", "BID", "");
        UUID inconnu = seed("Motif inventé", "ANNOUNCEMENT", "  ");
        UUID dejaCatalogue = seed("FAKE_PROFILE", "USER", "Photo volée");

        flywayUpTo("262").migrate();

        assertRow(paiement, "PAYMENT_ISSUE", "Le virement n'arrive pas");
        assertRow(bug, "APP_BUG", null);
        assertRow(autre, "OTHER", "Rien à voir");
        assertRow(utilisateur, "OTHER", "[Motif d'origine : Problème avec un utilisateur] Il ne répond plus");
        assertRow(colis, "OTHER", "[Motif d'origine : Problème avec un colis]");
        assertRow(inconnu, "OTHER", "[Motif d'origine : Motif inventé]");
        // Une ligne déjà au format catalogue n'est pas touchée.
        assertRow(dejaCatalogue, "FAKE_PROFILE", "Photo volée");
    }

    @Test
    void v262_interditDesormaisUnMotifHorsCatalogue() throws Exception {
        flywayUpTo("262").migrate();

        assertThatThrownBy(() -> seed("Problème avec un utilisateur", "USER", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_reports_reason");
        // Les dix codes du catalogue passent.
        for (String code : new String[]{"HARASSMENT", "FAKE_PROFILE", "SCAM_ATTEMPT", "PROHIBITED_ITEM",
                "FALSE_INFORMATION", "INAPPROPRIATE_CONTENT", "SPAM", "PAYMENT_ISSUE", "APP_BUG", "OTHER"}) {
            seed(code, "USER", null);
        }
    }

    private static UUID seed(String reason, String targetType, String description) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO reports (id, target_type, target_id, reason, description) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, targetType);
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, reason);
            ps.setString(5, description);
            ps.executeUpdate();
        }
        return id;
    }

    private static void assertRow(UUID id, String reason, String description) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT reason, description FROM reports WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("reason")).as("reason de %s", id).isEqualTo(reason);
            assertThat(rs.getString("description")).as("description de %s", id).isEqualTo(description);
        }
    }
}

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
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V265 : colonnes {@code question_en}/{@code answer_en} de
 * {@code support_predefined_replies} (traduction anglaise des questions
 * frequentes du support), rétro-remplies pour les 10 lignes du catalogue V244.
 * Le profil test tourne sur H2 sans Flyway : on migre un PostgreSQL embarqué
 * jusqu'à V264, on applique V265 et on vérifie sur le vrai moteur.
 */
class V265SupportPredefinedRepliesEnMigrationTest {

    private static final Set<String> EXPECTED_CODES = Set.of(
            "account-verification",
            "kyc-rejected",
            "payment-when-charged",
            "payment-refund-delay",
            "payout-delay",
            "delivery-qr-scan",
            "delivery-late",
            "trip-cancelled",
            "package-forbidden-items",
            "account-delete");

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
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("264");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void addsNullableEnglishColumns() throws Exception {
        flywayUpTo("265").migrate();

        assertThat(columnIsNullable("question_en")).isTrue();
        assertThat(columnIsNullable("answer_en")).isTrue();
    }

    @Test
    void translatesAllTenSeededReplies() throws Exception {
        flywayUpTo("265").migrate();

        Set<String> seenCodes = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT code, question_en, answer_en FROM support_predefined_replies
                     """)) {
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                String code = rs.getString("code");
                seenCodes.add(code);
                assertThat(rs.getString("question_en"))
                        .as("question_en for %s", code)
                        .isNotNull()
                        .isNotBlank();
                assertThat(rs.getString("answer_en"))
                        .as("answer_en for %s", code)
                        .isNotNull()
                        .isNotBlank();
            }
        }
        assertThat(seenCodes).containsExactlyInAnyOrderElementsOf(EXPECTED_CODES);
    }

    @Test
    void leavesTheFrenchColumnsUntouched() throws Exception {
        flywayUpTo("265").migrate();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT question, answer FROM support_predefined_replies WHERE code = 'account-delete'
                     """)) {
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("question")).isEqualTo("Comment supprimer mon compte ?");
            assertThat(rs.getString("answer")).contains("definitive");
        }
    }

    private boolean columnIsNullable(String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT is_nullable FROM information_schema.columns
                     WHERE table_name = 'support_predefined_replies' AND column_name = ?
                     """)) {
            statement.setString(1, column);
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).as("column %s exists", column).isTrue();
            return "YES".equalsIgnoreCase(rs.getString("is_nullable"));
        }
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }
}

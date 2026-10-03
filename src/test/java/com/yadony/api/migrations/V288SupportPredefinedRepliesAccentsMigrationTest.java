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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V288 : accents des questions fréquentes du support (FLUTTER-A0). V244 les
 * avait insérées sans aucun accent. On migre un PostgreSQL embarqué jusqu'à
 * V287, on applique V288 et on vérifie le texte servi par
 * {@code GET /support/replies}.
 */
class V288SupportPredefinedRepliesAccentsMigrationTest {

    /** Mots que V244 écrivait sans accent : aucun ne doit survivre. */
    private static final Pattern UNACCENTED = Pattern.compile(
            "\\b(etre|verifie|verification|identite|debite|delai|delais|reponse|"
                    + "rembourse|integralement|definitive|termines|prevue|"
                    + "especes|medicaments|perissables|illegal|declaree|plafonnee|"
                    + "Parametres|A quel|a echoue|des que|apres)\\b");

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
        Flyway baseline = flywayUpTo("287");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void accentsEveryFrenchReply() throws Exception {
        flywayUpTo("288").migrate();

        Map<String, String[]> replies = frenchReplies();
        assertThat(replies).hasSize(10);
        replies.forEach((code, qa) -> {
            assertThat(UNACCENTED.matcher(qa[0]).find()).as("question %s : %s", code, qa[0]).isFalse();
            assertThat(UNACCENTED.matcher(qa[1]).find()).as("answer %s : %s", code, qa[1]).isFalse();
        });
        assertThat(replies.get("account-verification")[0]).isEqualTo("Pourquoi mon compte doit-il être vérifié ?");
        assertThat(replies.get("payment-when-charged")[0]).isEqualTo("À quel moment suis-je débité ?");
    }

    @Test
    void pointsAccountDeletionToTheProfileMenu() throws Exception {
        flywayUpTo("288").migrate();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT answer, answer_en FROM support_predefined_replies WHERE code = 'account-delete'
                     """)) {
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("answer")).startsWith("Depuis l'onglet Moi, ouvrez le menu");
            assertThat(rs.getString("answer_en")).startsWith("From the Profile tab, open the menu");
        }
    }

    private Map<String, String[]> frenchReplies() throws Exception {
        Map<String, String[]> replies = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT code, question, answer FROM support_predefined_replies
                     """)) {
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                replies.put(rs.getString("code"), new String[] {rs.getString("question"), rs.getString("answer")});
            }
        }
        return replies;
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

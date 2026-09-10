package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V253 — index partiels des chemins de lecture chauds (demandes d'un trajet, colis d'un
 * expéditeur, fils de discussion actifs et archivés).
 *
 * <p>Le profil "test" tourne sur H2 avec Flyway désactivé et un schéma dérivé des entités :
 * il ne porte aucun de ces index. Comme pour {@link V246PawapayOperationsMigrationTest}, on
 * migre un PostgreSQL embarqué (zonky) jusqu'à la dernière version et on vérifie sur le vrai
 * moteur que (1) chaque index existe avec le prédicat attendu, donc que chaque colonne citée
 * existe bien dans le schéma courant, (2) le planificateur peut réellement s'en servir pour
 * les filtres de {@code ConversationRepository} — un prédicat partiel que la requête
 * n'implique pas produit un index parfaitement inutile, sans erreur, et (3) le script est
 * rejouable ({@code IF NOT EXISTS}).
 */
class V253ProductionHotPathIndexesMigrationTest {

    private static final String SCRIPT = "/db/migration/V253__production_hot_path_indexes.sql";

    /** Nom de l'index → fragments attendus dans {@code pg_indexes.indexdef}. */
    private static final Map<String, List<String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("idx_bids_announcement_active_created",
                List.of("ON public.bids", "(announcement_id, created_at DESC)", "deleted_at IS NULL"));
        EXPECTED.put("idx_bids_sender_active_created",
                List.of("ON public.bids", "(sender_id, created_at DESC)", "deleted_at IS NULL"));
        EXPECTED.put("idx_conversations_sender_active_updated",
                List.of("ON public.conversations", "(sender_id, updated_at DESC)",
                        "sender_deleted_at IS NULL", "sender_archived_at IS NULL"));
        EXPECTED.put("idx_conversations_traveler_active_updated",
                List.of("ON public.conversations", "(traveler_id, updated_at DESC)",
                        "traveler_deleted_at IS NULL", "traveler_archived_at IS NULL"));
        EXPECTED.put("idx_conversations_sender_archived_updated",
                List.of("ON public.conversations", "(sender_id, updated_at DESC)",
                        "sender_deleted_at IS NULL", "sender_archived_at IS NOT NULL"));
        EXPECTED.put("idx_conversations_traveler_archived_updated",
                List.of("ON public.conversations", "(traveler_id, updated_at DESC)",
                        "traveler_deleted_at IS NULL", "traveler_archived_at IS NOT NULL"));
    }

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabaseAndMigrateToLatest() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    // ─── (1) Existence et forme des index ────────────────────────────────────────

    @Test
    @DisplayName("les six index existent, sur les colonnes et avec les prédicats attendus")
    void allSixIndexes_exist_withExpectedColumnsAndPredicates() throws Exception {
        Map<String, String> actual = indexDefinitions(EXPECTED.keySet());

        assertThat(actual).containsOnlyKeys(EXPECTED.keySet());
        EXPECTED.forEach((name, fragments) ->
                assertThat(actual.get(name)).as(name).contains(fragments));
    }

    @Test
    @DisplayName("bids : le prédicat ne porte que deleted_at, pas les drapeaux par partie")
    void bidsIndexes_predicateIsOnlyDeletedAt() throws Exception {
        // Les drapeaux deleted_by_traveler / deleted_by_sender sont filtrés en mémoire par
        // BidService : un prédicat qui les porterait rendrait l'index inutilisable par
        // findByAnnouncementId / findBySenderId (cf. en-tête de la migration).
        Map<String, String> actual = indexDefinitions(
                List.of("idx_bids_announcement_active_created", "idx_bids_sender_active_created"));

        assertThat(actual.values()).allSatisfy(def ->
                assertThat(def).doesNotContain("deleted_by_traveler").doesNotContain("deleted_by_sender"));
    }

    @Test
    @DisplayName("les index de V3 / V31 sont conservés")
    void preExistingIndexes_areUntouched() throws Exception {
        Map<String, String> actual = indexDefinitions(List.of(
                "idx_bids_announcement_id", "idx_bids_sender_id",
                "idx_conversations_sender_id", "idx_conversations_traveler_id"));

        assertThat(actual).hasSize(4);
    }

    // ─── (2) Le planificateur retient bien l'index pour les filtres réels ────────

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "sender_id = ? AND sender_deleted_at IS NULL AND sender_archived_at IS NULL, idx_conversations_sender_active_updated",
            "traveler_id = ? AND traveler_deleted_at IS NULL AND traveler_archived_at IS NULL, idx_conversations_traveler_active_updated",
            "sender_id = ? AND sender_deleted_at IS NULL AND sender_archived_at IS NOT NULL, idx_conversations_sender_archived_updated",
            "traveler_id = ? AND traveler_deleted_at IS NULL AND traveler_archived_at IS NOT NULL, idx_conversations_traveler_archived_updated",
    })
    @DisplayName("chaque branche de findByParticipant / findArchivedByParticipant est servie par son index")
    void conversationFilter_isServedByItsPartialIndex(String where, String expectedIndex) throws Exception {
        // Chaque branche du OR de ConversationRepository, telle que Hibernate la génère.
        // enable_seqscan = off force le planificateur à préférer n'importe quel index
        // APPLICABLE au parcours séquentiel : si le nôtre n'apparaît pas dans le plan,
        // c'est que son prédicat n'est pas impliqué par la requête — le défaut exact des
        // index de V31 (conditionnés à deleted_at) pour ces requêtes.
        String plan = explainWithoutSeqScan(
                "SELECT id FROM conversations WHERE " + where + " ORDER BY updated_at DESC");

        assertThat(plan).as("plan pour « %s »", where).contains(expectedIndex);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "announcement_id = ? AND deleted_at IS NULL, idx_bids_announcement_active_created",
            "sender_id = ? AND deleted_at IS NULL, idx_bids_sender_active_created",
    })
    @DisplayName("les filtres de BidEntity (@Where deleted_at IS NULL) peuvent utiliser l'index partiel")
    void bidFilter_canUseItsPartialIndex(String where, String expectedIndex) throws Exception {
        // Les index pleins de V3 (announcement_id, sender_id, deleted_at) restent candidats
        // pour la même requête : on ne fige pas le choix du planificateur, seulement
        // l'applicabilité du nôtre, en le rendant seul candidat le temps de l'EXPLAIN
        // (transaction annulée ensuite).
        String plan = explainWithoutSeqScanHiding(
                "SELECT id FROM bids WHERE " + where + " ORDER BY created_at DESC",
                List.of("idx_bids_announcement_id", "idx_bids_sender_id", "idx_bids_deleted_at"));

        assertThat(plan).as("plan pour « %s »", where).contains(expectedIndex);
    }

    // ─── (3) Rejouable ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rejouer le script sur un schéma déjà migré ne lève rien (IF NOT EXISTS)")
    void replayingTheScript_isIdempotent() throws Exception {
        String body;
        try (var in = getClass().getResourceAsStream(SCRIPT)) {
            assertThat(in).as("script %s présent sur le classpath", SCRIPT).isNotNull();
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThatCode(() -> {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(body);
            }
        }).doesNotThrowAnyException();

        assertThat(indexDefinitions(EXPECTED.keySet())).hasSize(EXPECTED.size());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, String> indexDefinitions(Iterable<String> names) throws SQLException {
        Map<String, String> definitions = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
            for (String name : names) {
                statement.setString(1, name);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        definitions.put(rs.getString("indexname"), rs.getString("indexdef"));
                    }
                }
            }
        }
        return definitions;
    }

    private static String explainWithoutSeqScan(String sql) throws SQLException {
        return explainWithoutSeqScanHiding(sql, List.of());
    }

    /**
     * Plan d'exécution de {@code sql} (le {@code ?} reçoit un UUID quelconque), parcours
     * séquentiel découragé et, le temps de la transaction, sans les index listés — annulée
     * à la fin pour que les autres tests retrouvent le schéma intact.
     */
    private static String explainWithoutSeqScanHiding(String sql, List<String> indexesToHide)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan = off");
                for (String index : indexesToHide) {
                    statement.execute("DROP INDEX " + index);
                }
                try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
                    explain.setObject(1, UUID.randomUUID());
                    List<String> lines = new ArrayList<>();
                    try (ResultSet rs = explain.executeQuery()) {
                        while (rs.next()) {
                            lines.add(rs.getString(1));
                        }
                    }
                    return String.join("\n", lines);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}

package com.yadony.api.admin;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.signalements.ReportRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL embarqué avec le vrai Flyway (V353, {@code ddl-auto: validate}) : la table des notes
 * correspond à l'entité, et les requêtes de la file de modération (signalements ouverts par
 * conversation, recherche par nom) tournent sur le vrai moteur, pas sur le H2 laxiste des autres tests.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("AdminConversationModerationPersistenceTest : V353 et requêtes de la file sur PostgreSQL")
class AdminConversationModerationPersistenceTest {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired ConversationRepository conversations;
    @Autowired ConversationAdminNoteRepository notes;
    @Autowired ReportRepository reports;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void relaxForeignKeys() {
        // Les clés étrangères des conversations (bids, utilisateurs) n'ont rien à voir avec ce qui est testé.
        jdbc.execute("SET session_replication_role = replica");
    }

    private UUID insertUser(String first, String last) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username, first_name, last_name, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW(), 0)",
                id, "uid-" + id, "u" + id.toString().substring(0, 8), first, last);
        return id;
    }

    private UUID insertConversation(UUID a, UUID b, String firestoreId, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, bid_id, sender_id, traveler_id, firestore_conversation_id, kind, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'SENDER_TRAVELER', ?, ?)",
                id, UUID.randomUUID(), a, b, firestoreId, createdAt, createdAt);
        return id;
    }

    private void insertMessageReport(UUID conversationId, String status, boolean deleted) {
        jdbc.update("INSERT INTO reports (id, target_type, target_id, reporter_id, reason, status, created_at, updated_at, deleted_at) "
                        + "VALUES (?, 'MESSAGE', ?, ?, 'SPAM', ?, NOW(), NOW(), ?)",
                UUID.randomUUID(), conversationId, UUID.randomUUID(), status, deleted ? LocalDateTime.now() : null);
    }

    private static final java.util.Collection<UUID> NO_USERS = List.of(new UUID(0, 0));
    private static PageRequest page() {
        return PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    @DisplayName("la file « signalées » ne garde que les conversations avec un signalement OUVERT sur un message")
    void findForModeration_onlyFlagged_keepsOpenMessageReports() {
        UUID a = insertUser("Aicha", "Diallo");
        UUID b = insertUser("Moussa", "Traore");
        UUID open = insertConversation(a, b, "c-open", LocalDateTime.now().minusDays(1));
        UUID resolved = insertConversation(a, b, "c-resolved", LocalDateTime.now().minusDays(2));
        UUID deleted = insertConversation(a, b, "c-deleted", LocalDateTime.now().minusDays(3));
        UUID calm = insertConversation(a, b, "c-calm", LocalDateTime.now().minusDays(4));
        insertMessageReport(open, "OPEN", false);
        insertMessageReport(resolved, "RESOLVED", false);
        insertMessageReport(deleted, "OPEN", true);

        var flagged = conversations.findForModeration(true, false, NO_USERS, page()).getContent();
        var all = conversations.findForModeration(false, false, NO_USERS, page()).getContent();

        assertThat(flagged).extracting(ConversationEntity::getFirestoreConversationId).containsExactly("c-open");
        assertThat(all).extracting(ConversationEntity::getFirestoreConversationId)
                .contains("c-open", "c-resolved", "c-deleted", "c-calm");
        assertThat(flagged.get(0).getId()).isEqualTo(open);
    }

    @Test
    @DisplayName("les signalements ouverts sont comptés par identifiant INTERNE de conversation")
    void countOpenMessageReports_groupsByInternalId() {
        UUID a = insertUser("A", "A");
        UUID b = insertUser("B", "B");
        UUID two = insertConversation(a, b, "c-two", LocalDateTime.now());
        UUID one = insertConversation(a, b, "c-one", LocalDateTime.now());
        UUID none = insertConversation(a, b, "c-none", LocalDateTime.now());
        insertMessageReport(two, "OPEN", false);
        insertMessageReport(two, "OPEN", false);
        insertMessageReport(one, "OPEN", false);
        insertMessageReport(one, "DISMISSED", false);

        var rows = reports.countOpenMessageReports(List.of(two, one, none));

        assertThat(rows).extracting(r -> (UUID) r[0], r -> ((Number) r[1]).intValue())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(two, 2), org.assertj.core.groups.Tuple.tuple(one, 1));
    }

    @Test
    @DisplayName("la recherche par nom trouve un participant, sans tenir compte de la casse, des deux côtés")
    void findIdsByNameLike_andFilterByParticipants() {
        UUID aicha = insertUser("Aïcha", "Diallo");
        UUID moussa = insertUser("Moussa", "Traoré");
        UUID other = insertUser("Fatou", "Ba");
        UUID asSender = insertConversation(aicha, other, "c-sender", LocalDateTime.now().minusDays(1));
        UUID asTraveler = insertConversation(other, aicha, "c-traveler", LocalDateTime.now().minusDays(2));
        insertConversation(moussa, other, "c-unrelated", LocalDateTime.now().minusDays(3));

        List<UUID> ids = users.findIdsByNameLike("%aïcha%", PageRequest.of(0, 10));
        var found = conversations.findForModeration(false, true, ids, page()).getContent();

        assertThat(ids).containsExactly(aicha);
        assertThat(found).extracting(ConversationEntity::getFirestoreConversationId).containsExactly("c-sender", "c-traveler");
        assertThat(users.findIdsByNameLike("%diallo%", PageRequest.of(0, 10))).containsExactly(aicha);
        assertThat(users.findIdsByNameLike("%aïcha diallo%", PageRequest.of(0, 10))).containsExactly(aicha);
        assertThat(users.findIdsByNameLike("%introuvable%", PageRequest.of(0, 10))).isEmpty();
        assertThat(asSender).isNotNull();
        assertThat(asTraveler).isNotNull();
    }

    @Test
    @DisplayName("filtre signalées ET recherche se combinent")
    void findForModeration_flaggedAndSearch() {
        UUID aicha = insertUser("Aicha", "Diallo");
        UUID other = insertUser("Fatou", "Ba");
        UUID flaggedAicha = insertConversation(aicha, other, "c-fa", LocalDateTime.now().minusDays(1));
        UUID calmAicha = insertConversation(aicha, other, "c-ca", LocalDateTime.now().minusDays(2));
        UUID flaggedOther = insertConversation(other, other, "c-fo", LocalDateTime.now().minusDays(3));
        insertMessageReport(flaggedAicha, "OPEN", false);
        insertMessageReport(flaggedOther, "OPEN", false);

        var found = conversations.findForModeration(true, true, List.of(aicha), page()).getContent();

        assertThat(found).extracting(ConversationEntity::getFirestoreConversationId).containsExactly("c-fa");
        assertThat(calmAicha).isNotNull();
    }

    @Test
    @DisplayName("notes internes : enregistrement et relecture, la plus récente d'abord, par conversation")
    void notes_saveAndReadBackNewestFirst() {
        UUID conv = insertConversation(UUID.randomUUID(), UUID.randomUUID(), "c-notes", LocalDateTime.now());
        UUID admin = UUID.randomUUID();
        ConversationAdminNoteEntity first = new ConversationAdminNoteEntity();
        first.setConversationId(conv);
        first.setAdminId(admin);
        first.setContent("Première note");
        notes.saveAndFlush(first);
        jdbc.update("UPDATE conversation_admin_notes SET created_at = ? WHERE id = ?", LocalDateTime.now().minusHours(2), first.getId());
        ConversationAdminNoteEntity second = new ConversationAdminNoteEntity();
        second.setConversationId(conv);
        second.setAdminId(admin);
        second.setContent("Seconde note");
        notes.saveAndFlush(second);

        assertThat(notes.findByConversationIdOrderByCreatedAtDesc(conv)).extracting(ConversationAdminNoteEntity::getContent)
                .containsExactly("Seconde note", "Première note");
        assertThat(notes.findByConversationIdOrderByCreatedAtDesc(UUID.randomUUID())).isEmpty();
    }
}

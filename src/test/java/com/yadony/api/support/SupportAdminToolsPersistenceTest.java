package com.yadony.api.support;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL embarqué avec le vrai Flyway (V350) : les tables des notes et des réponses types existent
 * telles que les entités les valident ({@code ddl-auto: validate}), les cinq réponses types de départ
 * sont semées, et la requête du dernier message de chaque ticket est juste.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("SupportAdminToolsPersistenceTest — V350 sur PostgreSQL")
class SupportAdminToolsPersistenceTest {

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

    @Autowired SupportReplyTemplateRepository templateRepository;
    @Autowired SupportTicketNoteRepository noteRepository;
    @Autowired SupportMessageRepository messageRepository;
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private SupportTicketEntity ticket(UserEntity user, String subject) {
        SupportTicketEntity t = new SupportTicketEntity();
        t.setUserId(user.getId());
        t.setCategory("OTHER");
        t.setSubject(subject);
        t.setStatus(SupportTicketStatus.NEW);
        t.setLastMessageAt(LocalDateTime.now());
        return entityManager.persist(t);
    }

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername("user-" + UUID.randomUUID().toString().substring(0, 8));
        u.setStatus(UserStatus.ACTIVE);
        u.setRoles(Set.of(Role.SENDER));
        return entityManager.persist(u);
    }

    private SupportMessageEntity message(SupportTicketEntity t, String content, LocalDateTime at) {
        SupportMessageEntity m = new SupportMessageEntity();
        m.setTicketId(t.getId());
        m.setAuthorType(SupportMessageAuthorType.USER);
        m.setAuthorId(t.getUserId());
        m.setContent(content);
        entityManager.persist(m);
        entityManager.flush();
        jdbc.update("UPDATE support_messages SET created_at = ? WHERE id = ?", at, m.getId());
        entityManager.clear();
        return m;
    }

    @Test
    @DisplayName("les cinq réponses types de départ sont semées, dans l'ordre")
    void migration_seedsTheFiveStartingTemplates() {
        List<SupportReplyTemplateEntity> all = templateRepository.findAllByOrderBySortOrderAscCreatedAtAsc();

        assertThat(all).extracting(SupportReplyTemplateEntity::getLabel).containsExactly(
                "Demande de précisions", "Suivi du colis", "Vérification d'identité", "Pris en charge", "Clôture");
        assertThat(all.get(2).getContent()).contains("« Vérification d'identité »");
    }

    @Test
    @DisplayName("une réponse type supprimée (soft delete) sort de la liste mais sa ligne reste")
    void deletedTemplate_leavesTheListButNotTheTable() {
        SupportReplyTemplateEntity t = new SupportReplyTemplateEntity();
        t.setLabel("Éphémère");
        t.setContent("Texte");
        t.setSortOrder(99);
        templateRepository.saveAndFlush(t);
        t.setDeletedAt(LocalDateTime.now());
        templateRepository.saveAndFlush(t);
        entityManager.clear();

        assertThat(templateRepository.findAllByOrderBySortOrderAscCreatedAtAsc())
                .extracting(SupportReplyTemplateEntity::getLabel).doesNotContain("Éphémère");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_reply_templates WHERE label = 'Éphémère'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("les notes d'un ticket se relisent de la plus récente à la plus ancienne")
    void notes_areReadNewestFirst() {
        SupportTicketEntity t = ticket(user(), "Sujet");
        UUID admin = UUID.randomUUID();
        for (String text : new String[] {"Première note", "Seconde note"}) {
            SupportTicketNoteEntity n = new SupportTicketNoteEntity();
            n.setTicketId(t.getId());
            n.setAdminId(admin);
            n.setContent(text);
            entityManager.persist(n);
            entityManager.flush();
        }
        jdbc.update("UPDATE support_ticket_notes SET created_at = created_at - INTERVAL '1 hour' WHERE content = 'Première note'");
        entityManager.clear();

        assertThat(noteRepository.findByTicketIdOrderByCreatedAtDesc(t.getId()))
                .extracting(SupportTicketNoteEntity::getContent).containsExactly("Seconde note", "Première note");
    }

    @Test
    @DisplayName("le dernier message de chaque ticket, en une requête, sans les messages supprimés")
    void findLatestOfTickets_returnsOneLatestMessagePerTicket() {
        UserEntity u = user();
        SupportTicketEntity a = ticket(u, "A");
        SupportTicketEntity b = ticket(u, "B");
        SupportTicketEntity empty = ticket(u, "Vide");
        LocalDateTime now = LocalDateTime.now();
        message(a, "ancien", now.minusHours(5));
        message(a, "récent", now.minusHours(1));
        SupportMessageEntity deleted = message(a, "supprimé", now);
        jdbc.update("UPDATE support_messages SET deleted_at = NOW() WHERE id = ?", deleted.getId());
        message(b, "unique", now.minusHours(2));

        List<SupportMessageEntity> latest = messageRepository.findLatestOfTickets(List.of(a.getId(), b.getId(), empty.getId()));

        assertThat(latest).extracting(SupportMessageEntity::getContent).containsExactlyInAnyOrder("récent", "unique");
    }
}

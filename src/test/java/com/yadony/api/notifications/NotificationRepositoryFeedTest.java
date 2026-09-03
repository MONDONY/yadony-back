package com.yadony.api.notifications;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les requêtes du feed et de la boîte annonces : une annonce n'entre jamais
 * dans le feed, les non-lues d'un groupe replié en sortent, une lue du même
 * groupe y reste, et la lecture de groupe ne touche que les non-lues du
 * groupe de l'utilisateur.
 *
 * <p>Sur Postgres embarqué avec les migrations Flyway, pas sur H2 : la colonne
 * {@code data} est {@code jsonb} et H2 ne la relit pas en {@code Map}. Le
 * schéma réel (V238) est donc validé au passage.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Requêtes du feed et de la boîte annonces")
class NotificationRepositoryFeedTest {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
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

    @Autowired NotificationRepository repository;
    @PersistenceContext EntityManager entityManager;

    private final String annId = UUID.randomUUID().toString();
    private UUID userId;

    @BeforeEach
    void seedUser() {
        userId = newUser();
    }

    /** {@code notifications.user_id} est une clé étrangère : chaque propriétaire existe en base. */
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES (:id, :uid, :username, 'ACTIVE', now(), now())
                """)
                .setParameter("id", id)
                .setParameter("uid", "uid-" + id)
                .setParameter("username", "user-" + id.toString().substring(0, 12))
                .executeUpdate();
        return id;
    }

    private NotificationEntity save(UUID owner, String type, Map<String, String> data, boolean read) {
        var e = new NotificationEntity(owner, type, "Titre " + type, "Corps.", data, false);
        if (read) e.markRead(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(e);
    }

    private NotificationEntity bid(UUID owner, boolean read) {
        return save(owner, "BID_CREATED",
                Map.of("type", "BID_CREATED", "announcementId", annId, "bidId", UUID.randomUUID().toString()), read);
    }

    @Test
    void feed_excludesAnnouncementsAndCollapsedUnread_keepsReadLineOfSameGroup() {
        var unread1 = bid(userId, false);
        var unread2 = bid(userId, false);
        var unread3 = bid(userId, false);
        var readOfGroup = bid(userId, true);
        var payment = save(userId, "PAYMENT_RELEASED", Map.of("type", "PAYMENT_RELEASED"), false);
        var broadcast = save(userId, "ADMIN_BROADCAST", Map.of("type", "ADMIN_BROADCAST"), false);
        save(newUser(), "PAYMENT_RELEASED", Map.of("type", "PAYMENT_RELEASED"), false);
        entityManager.clear();

        var page = repository.findFeed(userId, NotificationCategory.ANNONCE,
                List.of("bid:announcement:" + annId), PageRequest.of(0, 30));

        assertThat(page.getContent()).extracting(NotificationEntity::getId)
                .containsExactlyInAnyOrder(readOfGroup.getId(), payment.getId())
                .doesNotContain(unread1.getId(), unread2.getId(), unread3.getId(), broadcast.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void feed_withSentinelKey_keepsEveryNonAnnouncement() {
        var a = bid(userId, false);
        var b = bid(userId, false);
        save(userId, "SYSTEM", Map.of("type", "SYSTEM"), false);
        entityManager.clear();

        var page = repository.findFeed(userId, NotificationCategory.ANNONCE,
                List.of(NotificationFeedService.NO_COLLAPSED_KEY), PageRequest.of(0, 30));

        assertThat(page.getContent()).extracting(NotificationEntity::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void unreadWithSharedKey_listsOnlyGroupCandidates() {
        var a = bid(userId, false);
        bid(userId, true);
        save(userId, "PAYMENT_RELEASED", Map.of("type", "PAYMENT_RELEASED"), false);
        entityManager.clear();

        var candidates = repository.findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(userId);

        assertThat(candidates).extracting(NotificationEntity::getId).containsExactly(a.getId());
    }

    @Test
    void markGroupRead_marksOnlyUnreadOfThatGroupForThatUser() {
        var a = bid(userId, false);
        var b = bid(userId, false);
        var alreadyRead = bid(userId, true);
        var other = save(userId, "PAYMENT_RELEASED", Map.of("type", "PAYMENT_RELEASED"), false);
        var someoneElse = bid(newUser(), false);
        entityManager.clear();

        int n = repository.markGroupRead(userId, "bid:announcement:" + annId, LocalDateTime.now(ZoneOffset.UTC));
        entityManager.clear();

        assertThat(n).isEqualTo(2);
        assertThat(repository.findById(a.getId()).orElseThrow().isRead()).isTrue();
        assertThat(repository.findById(b.getId()).orElseThrow().isRead()).isTrue();
        assertThat(repository.findById(alreadyRead.getId()).orElseThrow().isRead()).isTrue();
        assertThat(repository.findById(other.getId()).orElseThrow().isRead()).isFalse();
        assertThat(repository.findById(someoneElse.getId()).orElseThrow().isRead()).isFalse();
        assertThat(repository.countByUserIdAndReadAtIsNull(userId)).isEqualTo(1);
    }

    @Test
    void announcements_queriesServeOnlyThatCategory() {
        var broadcast = save(userId, "ADMIN_BROADCAST", Map.of("type", "ADMIN_BROADCAST"), false);
        var warning = save(userId, "ADMIN_WARNING", Map.of("type", "ADMIN_WARNING"), true);
        bid(userId, false);
        entityManager.clear();

        var page = repository.findByUserIdAndCategoryOrderByCreatedAtDesc(
                userId, NotificationCategory.ANNONCE, PageRequest.of(0, 30));

        assertThat(page.getContent()).extracting(NotificationEntity::getId)
                .containsExactlyInAnyOrder(broadcast.getId(), warning.getId());
        assertThat(repository.countByUserIdAndCategoryAndReadAtIsNull(userId, NotificationCategory.ANNONCE))
                .isEqualTo(1);
        assertThat(repository.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, NotificationCategory.ANNONCE))
                .isPresent();
    }
}

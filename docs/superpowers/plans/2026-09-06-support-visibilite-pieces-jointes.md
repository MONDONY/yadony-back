# Support : visibilité du fil et pièces jointes images — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre le fil support visible depuis l'onglet Messages avec un vrai compteur de non-lus et une notification push à chaque réponse admin, et permettre le partage d'images dans les deux sens.

**Architecture:** Le backend gagne une migration V245 (colonne de lecture + table de pièces jointes), quatre endpoints, un événement Spring consommé par `notifications/`, et un scheduler de purge. L'app Flutter injecte une ligne épinglée en tête de la liste des conversations et additionne son compteur REST au compteur Firestore existant. Le back-office affiche et envoie des images dans le fil.

**Tech Stack:** Spring Boot 3.4 / Java 21 / PostgreSQL 16 / Flyway / Cloudflare R2 (S3) / FCM — Flutter + flutter_bloc + Dio — Nuxt 4 / Vue 3 / Pinia / Vitest.

**Spec:** `docs/superpowers/specs/2026-09-06-support-visibilite-pieces-jointes-design.md`

## Global Constraints

- Répondre en français. Jamais de tiret cadratin dans un texte affiché à l'utilisateur.
- Le nom public affiché est **Yadony**, jamais « Dony ».
- Backend : `context-path` = `/api/v1`, donc un `@RequestMapping("/support")` répond sur `/api/v1/support`.
- Backend : toute erreur passe par `YadonyBusinessException` → `GlobalExceptionHandler` (RFC 7807). Jamais de `String` ni de `Map` brut.
- Backend : entités héritent de `BaseEntity`, soft delete via `@Where(clause = "deleted_at IS NULL")`. Aucun DELETE physique sur une entité métier.
- Backend : communication entre packages par événement Spring uniquement. Jamais d'injection croisée.
- Backend : un ticket d'autrui renvoie **404**, jamais 403.
- Backend : couverture JaCoCo ≥ 90 %. Ne jamais lancer deux `./mvnw` en parallèle (corrompt `target/classes`).
- Flutter : BLoC uniquement, jamais `setState`. GoRouter uniquement, jamais `Navigator.push`. Dio uniquement, jamais `http`. GetIt pour la DI.
- Flutter : tout nouvel event analytics est déclaré dans `AnalyticsEvents`, tiré depuis le BLoC, appelé avec `unawaited()`, sans aucune PII. La table d'events de `dony_app/CLAUDE.md` doit être mise à jour.
- Flutter : couverture ≥ 90 %. Ne jamais lancer deux commandes Flutter en parallèle (cache FVM partagé).
- Admin : seuil de couverture Vitest **global** — un composant non testé fait échouer tout le build.
- Git : jamais de commit sur `main`, jamais de `Co-Authored-By: Claude`. Travailler dans un worktree dédié.
- Images : 10 Mo max, `image/jpeg`, `image/jpg`, `image/png`, `image/webp` (déjà appliqué par `StorageService.validateFile`). Maximum **4** pièces jointes par message.
- URLs d'images : présignées **1 heure**. Jamais d'URL publique, jamais de clé brute dans une réponse.

---

## File Structure

**Backend — `dony-back/`**

| Fichier | Responsabilité |
|---|---|
| `src/main/resources/db/migration/V245__support_read_state_and_attachments.sql` | colonne `user_last_read_at` + table `support_message_attachments` |
| `src/main/java/com/yadony/api/support/SupportMessageAttachmentEntity.java` | entité pièce jointe |
| `src/main/java/com/yadony/api/support/SupportMessageAttachmentRepository.java` | accès aux pièces jointes |
| `src/main/java/com/yadony/api/support/SupportAttachmentService.java` | upload, contrôle de propriété des clés, résolution en URL présignée |
| `src/main/java/com/yadony/api/support/SupportAttachmentCleanupScheduler.java` | purge quotidienne des orphelins |
| `src/main/java/com/yadony/api/support/events/SupportMessageCreatedEvent.java` | événement publié à chaque message |
| `src/main/java/com/yadony/api/support/dto/SupportAttachmentResponse.java` | pièce jointe exposée (URL présignée) |
| `src/main/java/com/yadony/api/notifications/SupportMessageEventListener.java` | push FCM au propriétaire du ticket |
| *(modifiés)* `SupportTicketEntity`, `SupportMessageRepository`, `SupportTicketService`, `SupportController`, `AdminSupportController`, `SupportMessageResponse`, `SupportTicketResponse`, `CreateSupportTicketRequest`, `CreateSupportMessageRequest`, `StorageService` | |

**Flutter — `dony_app/`**

| Fichier | Responsabilité |
|---|---|
| `lib/features/support/data/support_attachment.dart` | modèle d'upload local (état + clé distante) |
| `lib/features/support/bloc/support_unread_cubit.dart` | compteur de non-lus pour le badge de l'onglet |
| `lib/features/support/presentation/widgets/support_conversation_tile.dart` | ligne épinglée « Support Yadony » |
| `lib/features/support/presentation/widgets/support_attachment_picker.dart` | trombone + vignettes d'upload |
| *(modifiés)* `support_repository.dart`, `support_models.dart`, `support_bloc.dart`, `support_state.dart`, `support_event.dart`, `support_ticket_detail_screen.dart`, `main_shell.dart`, la liste des conversations, `injection.dart`, `analytics_events.dart` | |

**Admin — `dony-admin/`**

| Fichier | Responsabilité |
|---|---|
| `app/features/support/components/SupportAttachmentGrid.vue` | vignettes + ouverture plein écran |
| `app/features/support/components/SupportAttachmentUploader.vue` | sélection et upload côté admin |
| *(modifiés)* `SupportTicketThread.vue`, `features/support/types/index.ts`, le service support | |

---

# Lot 1 — Backend

### Task 1 : Migration V245

**Files:**
- Create: `src/main/resources/db/migration/V245__support_read_state_and_attachments.sql`
- Test: `src/test/java/com/yadony/api/migrations/V245SupportReadStateAndAttachmentsMigrationTest.java`

**Interfaces:**
- Consomme : les tables `support_tickets` et `support_messages` créées par V244.
- Produit : colonne `support_tickets.user_last_read_at`, table `support_message_attachments`.

- [ ] **Étape 1 : vérifier que 245 est bien le prochain numéro libre**

```bash
cd /Users/aboubakardiakite/Desktop/dony/dony-back
git fetch origin
git ls-tree origin/main --name-only src/main/resources/db/migration/ | sort -V | tail -5
```

Si un `V245__*.sql` existe déjà sur `origin/main`, prendre le numéro suivant et le répercuter partout dans cette tâche (nom du fichier SQL, nom de la classe de test, et **l'argument de `flywayUpTo`, qui ne porte pas le préfixe `V`**). Comparer à `origin/main`, jamais au contenu de la branche : le test de migration part d'une base vide et ne verra jamais un conflit d'ordre avec la production.

- [ ] **Étape 2 : écrire le test de migration, qui doit échouer**

Créer `src/test/java/com/yadony/api/migrations/V245SupportReadStateAndAttachmentsMigrationTest.java` :

```java
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
 * V245 : etat de lecture cote utilisateur et pieces jointes des messages.
 */
class V245SupportReadStateAndAttachmentsMigrationTest {

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
        Flyway baseline = flywayUpTo("244");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void addsTheUserReadColumnNullableByDefault() throws Exception {
        flywayUpTo("245").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            UUID ticketId = seedTicket(connection, userId);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_last_read_at FROM support_tickets WHERE id = ?")) {
                statement.setObject(1, ticketId);
                ResultSet rs = statement.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("user_last_read_at")).isNull();
            }
        }
    }

    @Test
    void storesAnAttachmentAttachedToAMessage() throws Exception {
        flywayUpTo("245").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            UUID ticketId = seedTicket(connection, userId);
            UUID messageId = seedMessage(connection, ticketId, userId);
            seedAttachment(connection, messageId, "support/" + userId + "/1_a.jpg");

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT object_key, content_type, size_bytes
                    FROM support_message_attachments WHERE message_id = ?
                    """)) {
                statement.setObject(1, messageId);
                ResultSet rs = statement.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("object_key")).isEqualTo("support/" + userId + "/1_a.jpg");
                assertThat(rs.getString("content_type")).isEqualTo("image/jpeg");
                assertThat(rs.getLong("size_bytes")).isEqualTo(1024L);
            }
        }
    }

    /** Une piece jointe orpheline n'a aucun sens : la cle etrangere doit tenir. */
    @Test
    void rejectsAnAttachmentWithoutAnExistingMessage() throws Exception {
        flywayUpTo("245").migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> seedAttachment(connection, UUID.randomUUID(), "support/x/1_a.jpg"))
                    .isInstanceOf(SQLException.class);
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

    private UUID seedUser(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES ('%s', 'uid-%s', 'user-%s', 'ACTIVE', now(), now())
                    """.formatted(id, id, id.toString().substring(0, 12)));
        }
        return id;
    }

    private UUID seedTicket(Connection connection, UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_tickets
                    (id, user_id, category, subject, last_message_at, created_at, updated_at)
                VALUES (?, ?, 'PAYMENT', 'Paiement bloque', now(), now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID seedMessage(Connection connection, UUID ticketId, UUID authorId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_messages
                    (id, ticket_id, author_type, author_id, content, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, 'Bonjour', now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ticketId);
            statement.setObject(3, authorId);
            statement.executeUpdate();
        }
        return id;
    }

    private void seedAttachment(Connection connection, UUID messageId, String objectKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_message_attachments
                    (id, message_id, object_key, content_type, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, 'image/jpeg', 1024, now(), now())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, messageId);
            statement.setString(3, objectKey);
            statement.executeUpdate();
        }
    }
}
```

- [ ] **Étape 3 : lancer le test, vérifier qu'il échoue**

```bash
cd /Users/aboubakardiakite/Desktop/dony/dony-back/.worktrees/support-messaging
./mvnw test -Dtest=V245SupportReadStateAndAttachmentsMigrationTest
```

Attendu : ÉCHEC avec `FlywayException: No migration with a target version 245 could be found`.

- [ ] **Étape 4 : écrire la migration**

Créer `src/main/resources/db/migration/V245__support_read_state_and_attachments.sql` :

```sql
-- Etat de lecture cote utilisateur. NULL = jamais ouvert, donc tous les
-- messages admin comptent comme non lus. Distincte du statut du ticket : lire
-- n'est pas repondre, un WAITING_USER lu reste WAITING_USER.
ALTER TABLE support_tickets ADD COLUMN user_last_read_at TIMESTAMP;

-- Pieces jointes images d'un message, quatre au maximum (plafond applique en
-- Java : une contrainte SQL sur un COUNT couterait un trigger pour rien).
CREATE TABLE support_message_attachments (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES support_messages (id),
    -- Cle d'objet R2. Jamais exposee telle quelle : l'API rend une URL
    -- presignee d'une heure, une photo de justificatif ne doit pas etre
    -- devinable par quiconque connait la cle.
    object_key TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_message_attachments_message
    ON support_message_attachments (message_id);
```

- [ ] **Étape 5 : relancer le test, vérifier qu'il passe**

```bash
./mvnw test -Dtest=V245SupportReadStateAndAttachmentsMigrationTest
```

Attendu : `Tests run: 3, Failures: 0, Errors: 0`.

Si le test rejoue une ancienne version du SQL, supprimer la ressource compilée : `rm -f target/classes/db/migration/V245__*.sql`.

- [ ] **Étape 6 : commit**

```bash
git add src/main/resources/db/migration/V245__support_read_state_and_attachments.sql src/test/java/com/yadony/api/migrations/V245SupportReadStateAndAttachmentsMigrationTest.java
git commit -m "feat(support): migration V245 etat de lecture et pieces jointes"
```

---

### Task 2 : Entité et dépôt des pièces jointes

**Files:**
- Create: `src/main/java/com/yadony/api/support/SupportMessageAttachmentEntity.java`
- Create: `src/main/java/com/yadony/api/support/SupportMessageAttachmentRepository.java`
- Test: `src/test/java/com/yadony/api/support/SupportMessageAttachmentRepositoryTest.java`

**Interfaces:**
- Consomme : la table créée en Task 1.
- Produit :
  - `SupportMessageAttachmentEntity` avec `getMessageId() : UUID`, `getObjectKey() : String`, `getContentType() : String`, `getSizeBytes() : long` et leurs setters.
  - `SupportMessageAttachmentRepository.findByMessageIdInOrderByCreatedAtAsc(Collection<UUID>) : List<SupportMessageAttachmentEntity>`
  - `SupportMessageAttachmentRepository.findByMessageIdOrderByCreatedAtAsc(UUID) : List<SupportMessageAttachmentEntity>`
  - `SupportMessageAttachmentRepository.findObjectKeysByObjectKeyIn(Collection<String>) : List<String>`

- [ ] **Étape 1 : écrire le test, qui doit échouer**

Créer `src/test/java/com/yadony/api/support/SupportMessageAttachmentRepositoryTest.java` :

```java
package com.yadony.api.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupportMessageAttachmentRepositoryTest {

    @Autowired private SupportMessageAttachmentRepository repository;

    @Test
    void groupsAttachmentsByMessageInCreationOrder() {
        UUID messageA = UUID.randomUUID();
        UUID messageB = UUID.randomUUID();
        repository.save(attachment(messageA, "support/u/1_a.jpg"));
        repository.save(attachment(messageA, "support/u/2_b.jpg"));
        repository.save(attachment(messageB, "support/u/3_c.jpg"));

        List<SupportMessageAttachmentEntity> found =
                repository.findByMessageIdInOrderByCreatedAtAsc(List.of(messageA, messageB));

        assertThat(found).hasSize(3);
        assertThat(found).extracting(SupportMessageAttachmentEntity::getObjectKey)
                .containsExactlyInAnyOrder("support/u/1_a.jpg", "support/u/2_b.jpg", "support/u/3_c.jpg");
    }

    @Test
    void findsWhichKeysAreAlreadyReferenced() {
        repository.save(attachment(UUID.randomUUID(), "support/u/1_a.jpg"));

        List<String> referenced = repository.findObjectKeysByObjectKeyIn(
                List.of("support/u/1_a.jpg", "support/u/orphan.jpg"));

        assertThat(referenced).containsExactly("support/u/1_a.jpg");
    }

    private static SupportMessageAttachmentEntity attachment(UUID messageId, String key) {
        SupportMessageAttachmentEntity entity = new SupportMessageAttachmentEntity();
        entity.setMessageId(messageId);
        entity.setObjectKey(key);
        entity.setContentType("image/jpeg");
        entity.setSizeBytes(2048L);
        return entity;
    }
}
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=SupportMessageAttachmentRepositoryTest
```

Attendu : ÉCHEC de compilation, `SupportMessageAttachmentEntity` n'existe pas.

- [ ] **Étape 3 : écrire l'entité**

Créer `src/main/java/com/yadony/api/support/SupportMessageAttachmentEntity.java` :

```java
package com.yadony.api.support;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Image jointe a un message support. La cle d'objet ne sort jamais du backend :
 * l'API n'expose qu'une URL presignee de courte duree.
 */
@Entity
@Table(name = "support_message_attachments")
@Where(clause = "deleted_at IS NULL")
public class SupportMessageAttachmentEntity extends BaseEntity {

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "object_key", nullable = false, columnDefinition = "TEXT")
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    public UUID getMessageId() { return messageId; }

    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public String getObjectKey() { return objectKey; }

    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getContentType() { return contentType; }

    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }

    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
}
```

- [ ] **Étape 4 : écrire le dépôt**

Créer `src/main/java/com/yadony/api/support/SupportMessageAttachmentRepository.java` :

```java
package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SupportMessageAttachmentRepository
        extends JpaRepository<SupportMessageAttachmentEntity, UUID> {

    List<SupportMessageAttachmentEntity> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    List<SupportMessageAttachmentEntity> findByMessageIdInOrderByCreatedAtAsc(Collection<UUID> messageIds);

    @Query("SELECT a.objectKey FROM SupportMessageAttachmentEntity a WHERE a.objectKey IN :keys")
    List<String> findObjectKeysByObjectKeyIn(@Param("keys") Collection<String> keys);

    @Query("SELECT a.objectKey FROM SupportMessageAttachmentEntity a WHERE a.createdAt >= :since")
    List<String> findObjectKeysCreatedSince(@Param("since") LocalDateTime since);
}
```

- [ ] **Étape 5 : relancer le test, vérifier qu'il passe**

```bash
./mvnw test -Dtest=SupportMessageAttachmentRepositoryTest
```

Attendu : `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Étape 6 : commit**

```bash
git add src/main/java/com/yadony/api/support/SupportMessageAttachmentEntity.java src/main/java/com/yadony/api/support/SupportMessageAttachmentRepository.java src/test/java/com/yadony/api/support/SupportMessageAttachmentRepositoryTest.java
git commit -m "feat(support): entite et depot des pieces jointes"
```

---

### Task 3 : Compteur de non-lus et marquage de lecture

**Files:**
- Modify: `src/main/java/com/yadony/api/support/SupportTicketEntity.java`
- Modify: `src/main/java/com/yadony/api/support/SupportMessageRepository.java`
- Modify: `src/main/java/com/yadony/api/support/SupportTicketService.java`
- Modify: `src/main/java/com/yadony/api/support/dto/SupportTicketResponse.java`
- Modify: `src/main/java/com/yadony/api/support/SupportController.java`
- Test: `src/test/java/com/yadony/api/support/SupportUnreadServiceTest.java`

**Interfaces:**
- Consomme : `user_last_read_at` (Task 1).
- Produit :
  - `SupportTicketEntity.getUserLastReadAt() : LocalDateTime` / `setUserLastReadAt(LocalDateTime)`
  - `SupportMessageRepository.countByTicketIdAndAuthorTypeAndCreatedAtAfter(UUID, SupportMessageAuthorType, LocalDateTime) : long`
  - `SupportMessageRepository.countByTicketIdAndAuthorType(UUID, SupportMessageAuthorType) : long`
  - `SupportTicketService.unreadCount(SupportTicketEntity) : long`
  - `SupportTicketService.markRead(UserEntity, UUID) : void`
  - `SupportTicketService.totalUnread(UUID userId) : long`
  - `SupportTicketResponse` gagne le champ `long unreadCount` en dernière position ; `summary(ticket, unreadCount)` et `withMessages(ticket, messages, unreadCount)` remplacent les surcharges à deux arguments.
  - `POST /api/v1/support/tickets/{ticketId}/read` → 204
  - `GET /api/v1/support/unread-count` → `{ "count": n }`

- [ ] **Étape 1 : écrire le test unitaire, qui doit échouer**

Créer `src/test/java/com/yadony/api/support/SupportUnreadServiceTest.java` :

```java
package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportUnreadServiceTest {

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private SupportMessageRepository messageRepository;
    @Mock private SupportPredefinedReplyRepository replyRepository;
    @Mock private AdminAlertService adminAlertService;
    @Mock private AuditService auditService;
    @Mock private SupportAttachmentService attachmentService;

    @InjectMocks private SupportTicketService service;

    private UserEntity user;
    private SupportTicketEntity ticket;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        ticket = new SupportTicketEntity();
        ticket.setId(UUID.randomUUID());
        ticket.setUserId(user.getId());
        ticket.setStatus(SupportTicketStatus.WAITING_USER);
    }

    /** Jamais ouvert : toute reponse admin est non lue. */
    @Test
    void countsEveryAdminMessageWhenTheTicketWasNeverOpened() {
        ticket.setUserLastReadAt(null);
        when(messageRepository.countByTicketIdAndAuthorType(
                ticket.getId(), SupportMessageAuthorType.ADMIN)).thenReturn(3L);

        assertThat(service.unreadCount(ticket)).isEqualTo(3L);
    }

    @Test
    void countsOnlyAdminMessagesPostedAfterTheLastRead() {
        LocalDateTime readAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        ticket.setUserLastReadAt(readAt);
        when(messageRepository.countByTicketIdAndAuthorTypeAndCreatedAtAfter(
                ticket.getId(), SupportMessageAuthorType.ADMIN, readAt)).thenReturn(1L);

        assertThat(service.unreadCount(ticket)).isEqualTo(1L);
    }

    /** Lire n'est pas repondre : le statut ne bouge pas. */
    @Test
    void markingReadStampsTheDateWithoutTouchingTheStatus() {
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        service.markRead(user, ticket.getId());

        assertThat(ticket.getUserLastReadAt()).isNotNull();
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_USER);
        verify(ticketRepository).save(ticket);
    }

    /** Le ticket d'autrui est introuvable, jamais interdit. */
    @Test
    void refusesToMarkSomeoneElsesTicketWithA404() {
        UserEntity intruder = new UserEntity();
        intruder.setId(UUID.randomUUID());
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.markRead(intruder, ticket.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("introuvable");
        verify(ticketRepository, never()).save(any());
    }
}
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=SupportUnreadServiceTest
```

Attendu : ÉCHEC de compilation — `setUserLastReadAt`, `unreadCount`, `markRead` et `SupportAttachmentService` n'existent pas encore. `SupportAttachmentService` est créé en Task 4 : pour cette tâche, ajouter dès maintenant la dépendance au constructeur du service (elle ne sert qu'à partir de la Task 5), sinon écrire la Task 4 avant celle-ci.

- [ ] **Étape 3 : ajouter la colonne à l'entité**

Dans `SupportTicketEntity.java`, ajouter le champ et ses accesseurs à côté de `lastMessageAt` :

```java
    /**
     * Date de derniere ouverture du fil par l'utilisateur. NULL = jamais
     * ouvert. Ne bouge pas le statut : lire n'est pas repondre.
     */
    @Column(name = "user_last_read_at")
    private LocalDateTime userLastReadAt;

    public LocalDateTime getUserLastReadAt() { return userLastReadAt; }

    public void setUserLastReadAt(LocalDateTime userLastReadAt) { this.userLastReadAt = userLastReadAt; }
```

- [ ] **Étape 4 : ajouter les comptages au dépôt de messages**

Dans `SupportMessageRepository.java`, ajouter :

```java
    long countByTicketIdAndAuthorType(UUID ticketId, SupportMessageAuthorType authorType);

    long countByTicketIdAndAuthorTypeAndCreatedAtAfter(UUID ticketId,
                                                       SupportMessageAuthorType authorType,
                                                       LocalDateTime after);
```

Ajouter l'import `java.time.LocalDateTime`.

- [ ] **Étape 5 : implémenter le service**

Dans `SupportTicketService.java`, ajouter dans la section lecture :

```java
    /**
     * Nombre de messages admin que l'utilisateur n'a pas encore vus. Une date de
     * lecture nulle signifie « jamais ouvert », donc tout le fil admin compte.
     */
    @Transactional(readOnly = true)
    public long unreadCount(SupportTicketEntity ticket) {
        LocalDateTime readAt = ticket.getUserLastReadAt();
        return readAt == null
                ? messageRepository.countByTicketIdAndAuthorType(
                        ticket.getId(), SupportMessageAuthorType.ADMIN)
                : messageRepository.countByTicketIdAndAuthorTypeAndCreatedAtAfter(
                        ticket.getId(), SupportMessageAuthorType.ADMIN, readAt);
    }

    @Transactional(readOnly = true)
    public long totalUnread(UUID userId) {
        return ticketRepository.findByUserId(userId).stream()
                .mapToLong(this::unreadCount)
                .sum();
    }
```

et dans la section « cote user » :

```java
    /** Idempotent : reposer la date sur un fil deja lu ne change rien de visible. */
    public void markRead(UserEntity user, UUID ticketId) {
        SupportTicketEntity ticket = requireOwnedTicket(user, ticketId);
        ticket.setUserLastReadAt(now());
        ticketRepository.save(ticket);
    }
```

Ajouter dans `SupportTicketRepository.java` :

```java
    List<SupportTicketEntity> findByUserId(UUID userId);
```

- [ ] **Étape 6 : relancer le test unitaire, vérifier qu'il passe**

```bash
./mvnw test -Dtest=SupportUnreadServiceTest
```

Attendu : `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Étape 7 : exposer le compteur et les deux endpoints**

Dans `SupportTicketResponse.java`, ajouter `long unreadCount` en dernier champ du record et remplacer les fabriques :

```java
    /** Vue liste : le fil n'est pas charge. */
    public static SupportTicketResponse summary(SupportTicketEntity ticket, long unreadCount) {
        return build(ticket, null, unreadCount);
    }

    /** Vue detail : le fil complet, du plus ancien au plus recent. */
    public static SupportTicketResponse withMessages(SupportTicketEntity ticket,
                                                     List<SupportMessageResponse> messages,
                                                     long unreadCount) {
        return build(ticket, messages, unreadCount);
    }
```

`build` prend le compteur et le place en dernier argument du constructeur. Noter que `withMessages` reçoit désormais des `SupportMessageResponse` déjà construits : c'est la Task 5 qui les assemble avec leurs pièces jointes.

Dans `SupportController.java`, adapter les appels existants et ajouter :

```java
    @PostMapping("/tickets/{ticketId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal String firebaseUid,
                         @PathVariable UUID ticketId) {
        supportTicketService.markRead(requireUser(firebaseUid), ticketId);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal String firebaseUid) {
        UserEntity user = requireUser(firebaseUid);
        return Map.of("count", supportTicketService.totalUnread(user.getId()));
    }
```

Ajouter l'import `java.util.Map`.

- [ ] **Étape 8 : test d'intégration des deux endpoints**

Ajouter à `src/test/java/com/yadony/api/support/SupportControllerIntegrationTest.java`, en suivant le harnais d'authentification déjà présent dans ce fichier :

```java
    @Test
    void markingATicketReadResetsItsUnreadCount() throws Exception {
        // Le fil contient une reponse admin : le compteur vaut 1 avant lecture.
        mockMvc.perform(get("/support/tickets").with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unreadCount").value(1));

        mockMvc.perform(post("/support/tickets/" + ticketId + "/read").with(authenticatedUser()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/support/unread-count").with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void markingSomeoneElsesTicketReturns404() throws Exception {
        mockMvc.perform(post("/support/tickets/" + ticketId + "/read").with(anotherAuthenticatedUser()))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Étape 9 : lancer la suite support, vérifier qu'elle passe**

```bash
./mvnw test -Dtest='Support*Test,AdminSupport*Test'
```

Attendu : 0 échec.

- [ ] **Étape 10 : commit**

```bash
git add src/main/java/com/yadony/api/support src/test/java/com/yadony/api/support
git commit -m "feat(support): compteur de non-lus et marquage de lecture"
```

---

### Task 4 : Service d'upload des pièces jointes

**Files:**
- Create: `src/main/java/com/yadony/api/support/SupportAttachmentService.java`
- Create: `src/main/java/com/yadony/api/support/dto/SupportAttachmentResponse.java`
- Modify: `src/main/java/com/yadony/api/common/StorageService.java:36-37`
- Test: `src/test/java/com/yadony/api/support/SupportAttachmentServiceTest.java`

**Interfaces:**
- Consomme : `StorageService.uploadFile(MultipartFile, String) : String`, `StorageService.generatePresignedUrl(String, Duration) : String`, `SupportMessageAttachmentRepository` (Task 2).
- Produit :
  - `SupportAttachmentService.MAX_ATTACHMENTS_PER_MESSAGE = 4`
  - `uploadForUser(UUID userId, MultipartFile file) : String` (rend la clé)
  - `uploadForAdmin(UUID adminId, MultipartFile file) : String`
  - `requireOwnedKeys(List<String> keys, String expectedPrefix) : List<String>` — lève 422 si trop de clés ou si une clé sort du préfixe
  - `attach(UUID messageId, List<String> keys, String contentTypeFallback) : void`
  - `responsesFor(Collection<UUID> messageIds) : Map<UUID, List<SupportAttachmentResponse>>`
  - `userPrefix(UUID userId) : String` → `"support/{userId}/"`, `adminPrefix(UUID adminId) : String` → `"support/admin/{adminId}/"`
  - `SupportAttachmentResponse(UUID id, String url, String contentType, long sizeBytes)`

- [ ] **Étape 1 : écrire le test, qui doit échouer**

Créer `src/test/java/com/yadony/api/support/SupportAttachmentServiceTest.java` :

```java
package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class SupportAttachmentServiceTest {

    @Mock private StorageService storageService;
    @Mock private SupportMessageAttachmentRepository attachmentRepository;

    @InjectMocks private SupportAttachmentService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void acceptsKeysThatBelongToTheCaller() {
        List<String> keys = List.of(
                "support/" + userId + "/1_a.jpg",
                "support/" + userId + "/2_b.jpg");

        assertThat(service.requireOwnedKeys(keys, service.userPrefix(userId))).isEqualTo(keys);
    }

    /**
     * Sans ce controle, un utilisateur pourrait joindre a son message le fichier
     * d'un autre en devinant sa cle.
     */
    @Test
    void rejectsAKeyThatBelongsToSomeoneElse() {
        List<String> keys = List.of("support/" + UUID.randomUUID() + "/1_a.jpg");

        assertThatThrownBy(() -> service.requireOwnedKeys(keys, service.userPrefix(userId)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("piece jointe");
    }

    @Test
    void rejectsMoreThanFourAttachments() {
        List<String> keys = List.of(
                "support/" + userId + "/1.jpg", "support/" + userId + "/2.jpg",
                "support/" + userId + "/3.jpg", "support/" + userId + "/4.jpg",
                "support/" + userId + "/5.jpg");

        assertThatThrownBy(() -> service.requireOwnedKeys(keys, service.userPrefix(userId)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("4");
    }

    @Test
    void acceptsAnEmptyList() {
        assertThat(service.requireOwnedKeys(null, service.userPrefix(userId))).isEmpty();
        assertThat(service.requireOwnedKeys(List.of(), service.userPrefix(userId))).isEmpty();
    }

    @Test
    void buildsPrefixesFromIdentifiersNotFromRawInput() {
        UUID adminId = UUID.randomUUID();
        assertThat(service.userPrefix(userId)).isEqualTo("support/" + userId + "/");
        assertThat(service.adminPrefix(adminId)).isEqualTo("support/admin/" + adminId + "/");
    }
}
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=SupportAttachmentServiceTest
```

Attendu : ÉCHEC de compilation, `SupportAttachmentService` n'existe pas.

- [ ] **Étape 3 : autoriser le préfixe dans le stockage**

Dans `StorageService.java` ligne 36, ajouter `"support/"` à l'ensemble :

```java
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "tracking/", "users/", "messaging/", "kyc/", "package_requests/", "requests/", "bids/", "reports/",
            "support/");
```

- [ ] **Étape 4 : écrire le DTO**

Créer `src/main/java/com/yadony/api/support/dto/SupportAttachmentResponse.java` :

```java
package com.yadony.api.support.dto;

import java.util.UUID;

/**
 * {@code url} est presignee et expire en une heure. La cle d'objet n'est jamais
 * exposee : une photo de justificatif ne doit pas etre devinable.
 */
public record SupportAttachmentResponse(
        UUID id,
        String url,
        String contentType,
        long sizeBytes) {
}
```

- [ ] **Étape 5 : écrire le service**

Créer `src/main/java/com/yadony/api/support/SupportAttachmentService.java` :

```java
package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.support.dto.SupportAttachmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pieces jointes du support. L'upload est volontairement detache du ticket :
 * c'est ce qui permet de joindre une image au tout premier message, quand le
 * ticket n'existe pas encore.
 */
@Service
public class SupportAttachmentService {

    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 4;

    private static final Duration PRESIGNED_TTL = Duration.ofHours(1);

    private final StorageService storageService;
    private final SupportMessageAttachmentRepository attachmentRepository;

    public SupportAttachmentService(StorageService storageService,
                                    SupportMessageAttachmentRepository attachmentRepository) {
        this.storageService = storageService;
        this.attachmentRepository = attachmentRepository;
    }

    public String userPrefix(UUID userId) {
        return "support/" + userId + "/";
    }

    public String adminPrefix(UUID adminId) {
        return "support/admin/" + adminId + "/";
    }

    /** Le prefixe est construit a partir de l'identifiant, jamais d'une entree client. */
    public String uploadForUser(UUID userId, MultipartFile file) throws IOException {
        return storageService.uploadFile(file, userPrefix(userId));
    }

    public String uploadForAdmin(UUID adminId, MultipartFile file) throws IOException {
        return storageService.uploadFile(file, adminPrefix(adminId));
    }

    /**
     * Garde-fou central : sans lui, un message pourrait referencer la cle d'un
     * fichier appartenant a quelqu'un d'autre.
     */
    public List<String> requireOwnedKeys(List<String> keys, String expectedPrefix) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        if (keys.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-too-many-attachments", "Trop de pieces jointes",
                    "Un message accepte au maximum " + MAX_ATTACHMENTS_PER_MESSAGE + " images.");
        }
        for (String key : keys) {
            if (key == null || !key.startsWith(expectedPrefix)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "support-attachment-not-owned", "Piece jointe invalide",
                        "Une piece jointe ne vous appartient pas.");
            }
        }
        return keys;
    }

    public void attach(UUID messageId, List<String> keys, String contentType) {
        for (String key : keys) {
            SupportMessageAttachmentEntity entity = new SupportMessageAttachmentEntity();
            entity.setMessageId(messageId);
            entity.setObjectKey(key);
            entity.setContentType(contentType);
            entity.setSizeBytes(0L);
            attachmentRepository.save(entity);
        }
    }

    public Map<UUID, List<SupportAttachmentResponse>> responsesFor(Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        return attachmentRepository.findByMessageIdInOrderByCreatedAtAsc(messageIds).stream()
                .collect(Collectors.groupingBy(
                        SupportMessageAttachmentEntity::getMessageId,
                        Collectors.mapping(this::toResponse, Collectors.toList())));
    }

    private SupportAttachmentResponse toResponse(SupportMessageAttachmentEntity entity) {
        return new SupportAttachmentResponse(
                entity.getId(),
                storageService.generatePresignedUrl(entity.getObjectKey(), PRESIGNED_TTL),
                entity.getContentType(),
                entity.getSizeBytes());
    }
}
```

`attach` reçoit la taille et le type réels depuis l'appelant en Task 5 ; ici `sizeBytes` vaut 0 tant que rien ne l'alimente. La Task 5 remplace `attach` par une surcharge qui prend la taille, ce qui évite d'inventer un contrat inutile maintenant.

- [ ] **Étape 6 : relancer le test, vérifier qu'il passe**

```bash
./mvnw test -Dtest=SupportAttachmentServiceTest
```

Attendu : `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Étape 7 : commit**

```bash
git add src/main/java/com/yadony/api/support/SupportAttachmentService.java src/main/java/com/yadony/api/support/dto/SupportAttachmentResponse.java src/main/java/com/yadony/api/common/StorageService.java src/test/java/com/yadony/api/support/SupportAttachmentServiceTest.java
git commit -m "feat(support): service d'upload des pieces jointes"
```

---

### Task 5 : Endpoints d'upload et messages avec pièces jointes

**Files:**
- Modify: `src/main/java/com/yadony/api/support/SupportController.java`
- Modify: `src/main/java/com/yadony/api/admin/AdminSupportController.java`
- Modify: `src/main/java/com/yadony/api/support/SupportTicketService.java`
- Modify: `src/main/java/com/yadony/api/support/dto/CreateSupportTicketRequest.java`
- Modify: `src/main/java/com/yadony/api/support/dto/CreateSupportMessageRequest.java`
- Modify: `src/main/java/com/yadony/api/support/dto/SupportMessageResponse.java`
- Test: `src/test/java/com/yadony/api/support/SupportAttachmentIntegrationTest.java`

**Interfaces:**
- Consomme : `SupportAttachmentService` (Task 4), `SupportTicketResponse.withMessages(ticket, messages, unreadCount)` (Task 3).
- Produit :
  - `POST /api/v1/support/attachments` (multipart `file`) → `{ "key": "...", "url": "..." }`
  - `POST /api/v1/admin/support/tickets/attachments` (multipart `file`, `SUPPORT_TICKET_MANAGE`) → idem
  - `CreateSupportTicketRequest(String category, String subject, String message, List<String> attachmentKeys)`
  - `CreateSupportMessageRequest(String content, List<String> attachmentKeys)` — `content` n'est plus `@NotBlank`
  - `SupportMessageResponse(UUID id, String authorType, String content, LocalDateTime createdAt, List<SupportAttachmentResponse> attachments)`
  - `SupportTicketService.createTicket(UserEntity, String, String, String, List<String>) : SupportTicketEntity`
  - `SupportTicketService.userReply(UserEntity, UUID, String, List<String>) : SupportMessageEntity`
  - `SupportTicketService.adminReply(UUID, UUID, String, List<String>) : SupportMessageEntity`

- [ ] **Étape 1 : écrire le test d'intégration, qui doit échouer**

Créer `src/test/java/com/yadony/api/support/SupportAttachmentIntegrationTest.java`, en réutilisant le harnais d'authentification de `SupportControllerIntegrationTest` :

```java
    @Test
    void acceptsAMessageMadeOfAnImageWithoutAnyText() throws Exception {
        String key = uploadImage();   // POST /support/attachments

        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": null, "attachmentKeys": ["%s"]}
                                """.formatted(key)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].url").exists())
                .andExpect(jsonPath("$.attachments[0].objectKey").doesNotExist());
    }

    @Test
    void refusesAMessageWithNeitherTextNorImage() throws Exception {
        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"   \", \"attachmentKeys\": []}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesAKeyBelongingToAnotherUser() throws Exception {
        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Voici", "attachmentKeys": ["support/%s/1_a.jpg"]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesMoreThanFourImages() throws Exception {
        String keys = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> "\"support/" + userId + "/" + i + "_a.jpg\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Voici\", \"attachmentKeys\": [" + keys + "]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesAnAttachmentUploadFromAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(multipart("/support/attachments")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpegBytes())))
                .andExpect(status().isUnauthorized());
    }
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=SupportAttachmentIntegrationTest
```

Attendu : ÉCHEC — l'endpoint `/support/attachments` renvoie 404 et le champ `attachmentKeys` est ignoré.

- [ ] **Étape 3 : élargir les requêtes**

`CreateSupportTicketRequest.java` :

```java
package com.yadony.api.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSupportTicketRequest(
        @NotBlank String category,
        @NotBlank @Size(max = 200) String subject,
        @Size(max = 4000) String message,
        List<String> attachmentKeys) {
}
```

`CreateSupportMessageRequest.java` :

```java
package com.yadony.api.support.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code content} n'est plus obligatoire : un message peut n'etre qu'une image.
 * La regle « texte non vide OU au moins une piece jointe » porte sur deux
 * champs a la fois, elle vit donc dans le service et non dans une annotation.
 */
public record CreateSupportMessageRequest(
        @Size(max = 4000) String content,
        List<String> attachmentKeys) {
}
```

- [ ] **Étape 4 : élargir la réponse message**

`SupportMessageResponse.java` gagne un champ et une fabrique :

```java
    public static SupportMessageResponse from(SupportMessageEntity entity,
                                              List<SupportAttachmentResponse> attachments) {
        return new SupportMessageResponse(
                entity.getId(),
                entity.getAuthorType().name(),
                entity.getContent(),
                entity.getCreatedAt(),
                attachments == null ? List.of() : attachments);
    }
```

Supprimer la fabrique à un argument : laisser les deux ferait oublier les pièces jointes sur un appelant.

- [ ] **Étape 5 : appliquer la règle croisée dans le service**

Dans `SupportTicketService.java`, remplacer `requireText` par une validation qui tolère un contenu vide accompagné d'images :

```java
    /**
     * Texte non vide OU au moins une image. Rend une chaine vide plutot que
     * null : la colonne content est NOT NULL depuis V244.
     */
    private static String requireContentOrAttachments(String content, List<String> keys) {
        boolean hasText = content != null && !content.isBlank();
        boolean hasImage = keys != null && !keys.isEmpty();
        if (!hasText && !hasImage) {
            throw invalidField("message", "Ecrivez un message ou joignez une image");
        }
        if (!hasText) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw invalidField("message", "Le champ message depasse " + MAX_MESSAGE_LENGTH + " caracteres");
        }
        return trimmed;
    }
```

`createTicket`, `userReply` et `adminReply` prennent chacun un `List<String> attachmentKeys`, appellent `attachmentService.requireOwnedKeys(keys, prefix)` avec le préfixe de l'acteur, puis `attachmentService.attach(message.getId(), ownedKeys, ...)` après `appendMessage`.

- [ ] **Étape 6 : ajouter les deux endpoints d'upload**

Dans `SupportController.java` :

```java
    @PostMapping("/attachments")
    public Map<String, String> uploadAttachment(@AuthenticationPrincipal String firebaseUid,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        UserEntity user = requireUser(firebaseUid);
        String key = attachmentService.uploadForUser(user.getId(), file);
        return Map.of("key", key,
                "url", storageService.generatePresignedUrl(key, Duration.ofHours(1)));
    }
```

Dans `AdminSupportController.java`, au même niveau que les autres actions :

```java
    @PostMapping("/attachments")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public Map<String, String> uploadAttachment(@RequestParam("file") MultipartFile file) throws IOException {
        UUID adminId = currentAdminId();
        String key = attachmentService.uploadForAdmin(adminId, file);
        return Map.of("key", key,
                "url", storageService.generatePresignedUrl(key, Duration.ofHours(1)));
    }
```

`currentAdminId()` est le helper existant du controller, qui extrait l'`AdminPrincipal` du contexte de sécurité (ligne 171 du fichier).

- [ ] **Étape 7 : tracer l'ajout d'une pièce jointe par un admin**

Dans `SupportTicketService.adminReply`, après l'attachement des clés, quand la liste n'est pas vide :

```java
        if (!ownedKeys.isEmpty()) {
            auditService.log(AUDIT_ENTITY, ticket.getId(), "SUPPORT_TICKET_ADMIN_ATTACHED", adminId,
                    payload("messageId", String.valueOf(message.getId()),
                            "attachmentCount", String.valueOf(ownedKeys.size())));
        }
```

L'upload utilisateur n'est pas audité : le message lui-même en fait foi. Le geste d'un admin, lui, doit laisser une trace nominative.

Ajouter le test correspondant à `SupportAttachmentIntegrationTest` :

```java
    @Test
    void logsAnAuditEntryWhenAnAdminAttachesAnImage() throws Exception {
        // ... admin assigne repond avec une image
        assertThat(auditLogRepository.findAll())
                .extracting(AuditLogEntity::getAction)
                .contains("SUPPORT_TICKET_ADMIN_ATTACHED");
    }
```

- [ ] **Étape 8 : relancer les tests, vérifier qu'ils passent**

```bash
./mvnw test -Dtest='Support*Test,AdminSupport*Test'
```

Attendu : 0 échec.

- [ ] **Étape 9 : commit**

```bash
git add src/main/java/com/yadony/api/support src/main/java/com/yadony/api/admin/AdminSupportController.java src/test/java/com/yadony/api/support
git commit -m "feat(support): pieces jointes images dans les deux sens"
```

---

### Task 6 : Notification push sur réponse admin

**Files:**
- Create: `src/main/java/com/yadony/api/support/events/SupportMessageCreatedEvent.java`
- Create: `src/main/java/com/yadony/api/notifications/SupportMessageEventListener.java`
- Modify: `src/main/java/com/yadony/api/support/SupportTicketService.java`
- Test: `src/test/java/com/yadony/api/notifications/SupportMessageEventListenerTest.java`

**Interfaces:**
- Consomme : `NotificationDispatcher.notifyUser(UUID userId, String title, String body, Map<String,String> data) : void`.
- Produit : `SupportMessageCreatedEvent(UUID ticketId, UUID messageId, UUID ownerUserId, SupportMessageAuthorType authorType)` avec accesseurs `getTicketId()`, `getMessageId()`, `getOwnerUserId()`, `getAuthorType()`.

- [ ] **Étape 1 : écrire le test, qui doit échouer**

Créer `src/test/java/com/yadony/api/notifications/SupportMessageEventListenerTest.java` :

```java
package com.yadony.api.notifications;

import com.yadony.api.support.SupportMessageAuthorType;
import com.yadony.api.support.events.SupportMessageCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SupportMessageEventListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private SupportMessageEventListener listener;

    private final UUID ticketId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void notifiesTheTicketOwnerWhenAnAdminReplies() {
        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.ADMIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), anyString(), data.capture());

        assertThat(data.getValue()).containsEntry("type", "SUPPORT_MESSAGE");
        assertThat(data.getValue()).containsEntry("ticketId", ticketId.toString());
    }

    /** Le propre message de l'utilisateur ne doit pas lui revenir en push. */
    @Test
    void staysSilentWhenTheAuthorIsTheUser() {
        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.USER));

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }
}
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=SupportMessageEventListenerTest
```

Attendu : ÉCHEC de compilation, l'événement et le listener n'existent pas.

- [ ] **Étape 3 : écrire l'événement**

Créer `src/main/java/com/yadony/api/support/events/SupportMessageCreatedEvent.java` :

```java
package com.yadony.api.support.events;

import com.yadony.api.support.SupportMessageAuthorType;

import java.util.UUID;

/**
 * Publie a chaque message d'un fil support. Le package notifications l'ecoute
 * pour prevenir le proprietaire du ticket quand l'auteur est un admin.
 */
public class SupportMessageCreatedEvent {

    private final UUID ticketId;
    private final UUID messageId;
    private final UUID ownerUserId;
    private final SupportMessageAuthorType authorType;

    public SupportMessageCreatedEvent(UUID ticketId, UUID messageId, UUID ownerUserId,
                                      SupportMessageAuthorType authorType) {
        this.ticketId = ticketId;
        this.messageId = messageId;
        this.ownerUserId = ownerUserId;
        this.authorType = authorType;
    }

    public UUID getTicketId() { return ticketId; }

    public UUID getMessageId() { return messageId; }

    public UUID getOwnerUserId() { return ownerUserId; }

    public SupportMessageAuthorType getAuthorType() { return authorType; }
}
```

- [ ] **Étape 4 : écrire le listener**

Créer `src/main/java/com/yadony/api/notifications/SupportMessageEventListener.java` :

```java
package com.yadony.api.notifications;

import com.yadony.api.support.SupportMessageAuthorType;
import com.yadony.api.support.events.SupportMessageCreatedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Push simple, sans repli SMS : une reponse du support n'a pas la criticite
 * d'un evenement de livraison. AFTER_COMMIT, sinon la notification pointerait
 * vers un message que la base n'a pas encore.
 */
@Component
public class SupportMessageEventListener {

    private final NotificationDispatcher notificationDispatcher;

    public SupportMessageEventListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSupportMessage(SupportMessageCreatedEvent event) {
        if (event.getAuthorType() != SupportMessageAuthorType.ADMIN) {
            return;
        }
        notificationDispatcher.notifyUser(
                event.getOwnerUserId(),
                "Le support vous a repondu",
                "Ouvrez votre demande pour lire la reponse.",
                Map.of("type", "SUPPORT_MESSAGE",
                        "ticketId", event.getTicketId().toString()));
    }
}
```

- [ ] **Étape 5 : publier l'événement**

Dans `SupportTicketService.java`, injecter `ApplicationEventPublisher eventPublisher` au constructeur et publier à la fin de `appendMessage` :

```java
        eventPublisher.publishEvent(new SupportMessageCreatedEvent(
                ticket.getId(), saved.getId(), ticket.getUserId(), authorType));
```

Publier depuis `appendMessage` couvre les trois chemins d'écriture (création de ticket, réponse utilisateur, réponse admin) sans les dupliquer.

- [ ] **Étape 6 : relancer les tests, vérifier qu'ils passent**

```bash
./mvnw test -Dtest='SupportMessageEventListenerTest,Support*Test'
```

Attendu : 0 échec.

- [ ] **Étape 7 : commit**

```bash
git add src/main/java/com/yadony/api/support/events src/main/java/com/yadony/api/notifications/SupportMessageEventListener.java src/main/java/com/yadony/api/support/SupportTicketService.java src/test/java/com/yadony/api/notifications/SupportMessageEventListenerTest.java
git commit -m "feat(support): push a l'utilisateur quand le support repond"
```

---

### Task 7 : Purge des pièces jointes orphelines

**Files:**
- Create: `src/main/java/com/yadony/api/support/SupportAttachmentCleanupScheduler.java`
- Modify: `src/main/java/com/yadony/api/common/StorageService.java`
- Test: `src/test/java/com/yadony/api/support/SupportAttachmentCleanupSchedulerTest.java`

**Interfaces:**
- Consomme : `SupportMessageAttachmentRepository.findObjectKeysByObjectKeyIn(Collection<String>)` (Task 2).
- Produit : `StorageService.listKeysOlderThan(String prefix, Instant cutoff) : List<String>` et le scheduler `purgeOrphanAttachments()`.

- [ ] **Étape 1 : écrire le test, qui doit échouer**

Créer `src/test/java/com/yadony/api/support/SupportAttachmentCleanupSchedulerTest.java` :

```java
package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportAttachmentCleanupSchedulerTest {

    @Mock private StorageService storageService;
    @Mock private SupportMessageAttachmentRepository attachmentRepository;

    @InjectMocks private SupportAttachmentCleanupScheduler scheduler;

    @Test
    void deletesOnlyTheKeysNoMessageReferences() {
        when(storageService.listKeysOlderThan(eq("support/"), any(Instant.class)))
                .thenReturn(List.of("support/u/attached.jpg", "support/u/orphan.jpg"));
        when(attachmentRepository.findObjectKeysByObjectKeyIn(
                List.of("support/u/attached.jpg", "support/u/orphan.jpg")))
                .thenReturn(List.of("support/u/attached.jpg"));

        scheduler.purgeOrphanAttachments();

        verify(storageService).deleteFile("support/u/orphan.jpg");
        verify(storageService, never()).deleteFile("support/u/attached.jpg");
    }

    /** Idempotent : sans candidat, aucun appel de suppression. */
    @Test
    void doesNothingWhenThereIsNoCandidate() {
        when(storageService.listKeysOlderThan(eq("support/"), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.purgeOrphanAttachments();

        verify(storageService, never()).deleteFile(anyString());
    }
}
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
./mvnw test -Dtest=SupportAttachmentCleanupSchedulerTest
```

Attendu : ÉCHEC de compilation, `listKeysOlderThan` et le scheduler n'existent pas.

- [ ] **Étape 3 : ajouter le listage au stockage**

Dans `StorageService.java`, à côté de `deleteByPrefix` :

```java
    /** Cles d'un prefixe dont la date de derniere modification precede le seuil. */
    public List<String> listKeysOlderThan(String prefix, Instant cutoff) {
        validatePrefix(prefix);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        return s3Client.listObjectsV2Paginator(request).contents().stream()
                .filter(o -> o.lastModified().isBefore(cutoff))
                .map(S3Object::key)
                .toList();
    }
```

Ajouter les imports `software.amazon.awssdk.services.s3.model.ListObjectsV2Request`, `software.amazon.awssdk.services.s3.model.S3Object`, `java.time.Instant` et `java.util.List`.

- [ ] **Étape 4 : écrire le scheduler**

Créer `src/main/java/com/yadony/api/support/SupportAttachmentCleanupScheduler.java` :

```java
package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Une image uploadee puis abandonnee (l'utilisateur ferme l'app avant d'envoyer)
 * resterait sur R2 sans ligne d'attachement. Purge quotidienne, idempotente.
 */
@Component
public class SupportAttachmentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupportAttachmentCleanupScheduler.class);

    private static final String PREFIX = "support/";
    /** Large : un envoi lent ne doit jamais voir son image disparaitre sous lui. */
    private static final Duration GRACE_PERIOD = Duration.ofHours(24);

    private final StorageService storageService;
    private final SupportMessageAttachmentRepository attachmentRepository;

    public SupportAttachmentCleanupScheduler(StorageService storageService,
                                             SupportMessageAttachmentRepository attachmentRepository) {
        this.storageService = storageService;
        this.attachmentRepository = attachmentRepository;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void purgeOrphanAttachments() {
        List<String> candidates = storageService.listKeysOlderThan(
                PREFIX, Instant.now().minus(GRACE_PERIOD));
        if (candidates.isEmpty()) {
            return;
        }
        Set<String> referenced = Set.copyOf(attachmentRepository.findObjectKeysByObjectKeyIn(candidates));

        int purged = 0;
        for (String key : candidates) {
            if (referenced.contains(key)) {
                continue;
            }
            try {
                storageService.deleteFile(key);
                purged++;
            } catch (RuntimeException e) {
                log.warn("SupportAttachmentCleanup: suppression impossible pour {} : {}", key, e.getMessage());
            }
        }
        if (purged > 0) {
            log.info("SupportAttachmentCleanup: {} pieces jointes orphelines purgees", purged);
        }
    }
}
```

- [ ] **Étape 5 : relancer le test, vérifier qu'il passe**

```bash
./mvnw test -Dtest=SupportAttachmentCleanupSchedulerTest
```

Attendu : `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Étape 6 : purger les images à la suppression de compte**

Localiser le service qui applique la suppression de compte RGPD (il appelle déjà `storageService.deleteByPrefix` pour les autres préfixes de l'utilisateur) et y ajouter le préfixe support :

```java
        storageService.deleteByPrefix("support/" + user.getId() + "/");
```

Sans cette ligne, les images d'un compte supprimé resteraient sur R2 indéfiniment : le scheduler de la présente tâche ne touche qu'aux orphelines, et celles-ci sont rattachées à des messages.

Ajouter le test de régression dans la classe de test de suppression de compte existante :

```java
    @Test
    void deletesSupportAttachmentsWhenTheAccountIsRemoved() {
        service.deleteAccount(user.getId());

        verify(storageService).deleteByPrefix("support/" + user.getId() + "/");
    }
```

- [ ] **Étape 7 : lancer la suite complète et la couverture**

```bash
./mvnw test
./mvnw jacoco:report
```

Attendu : 0 échec, couverture globale ≥ 90 %. Ne jamais lancer un second `mvnw` en parallèle.

- [ ] **Étape 8 : commit**

```bash
git add src/main/java/com/yadony/api/support/SupportAttachmentCleanupScheduler.java src/main/java/com/yadony/api/common/StorageService.java src/test/java/com/yadony/api/support/SupportAttachmentCleanupSchedulerTest.java
git commit -m "feat(support): purge quotidienne des pieces jointes orphelines"
```

---

# Lot 2 — Application Flutter

> Le lot 1 doit être fusionné et déployé avant de commencer : ces tâches consomment des endpoints qui n'existent nulle part ailleurs.
>
> Toutes les commandes se lancent depuis `dony_app/.worktrees/<worktree>`. Ne jamais lancer deux commandes Flutter en parallèle : le cache FVM est partagé et produit de faux échecs.

### Task 8 : Modèles et accès réseau

**Files:**
- Create: `lib/features/support/data/support_attachment.dart`
- Modify: `lib/features/support/data/support_models.dart`
- Modify: `lib/features/support/data/support_repository.dart`
- Test: `test/features/support/support_repository_test.dart`

**Interfaces:**
- Consomme : les endpoints de la Task 5 et de la Task 3.
- Produit :
  - `SupportAttachment({required String id, required String url, required String contentType})` avec `SupportAttachment.fromJson(Map<String, dynamic>)`
  - `SupportMessage` gagne `final List<SupportAttachment> attachments`
  - `SupportTicket` gagne `final int unreadCount`
  - `SupportRepository.markRead(String ticketId) : Future<void>`
  - `SupportRepository.loadUnreadCount() : Future<int>`
  - `SupportRepository.uploadAttachment(String filePath) : Future<String>` (rend la clé)
  - `SupportRepository.createTicket(..., List<String> attachmentKeys)` et `sendMessage(String ticketId, String content, List<String> attachmentKeys)`
  - `SupportAttachmentUpload({required String localId, required String localPath, required SupportUploadStatus status, String? remoteKey})` avec `enum SupportUploadStatus { uploading, ready, failed }`

- [ ] **Étape 1 : écrire le test, qui doit échouer**

Créer `test/features/support/support_repository_test.dart` avec un `DioAdapter` mocké, sur le modèle des tests de dépôt existants :

```dart
  test('rend le nombre de non-lus renvoye par le serveur', () async {
    dioAdapter.onGet('/support/unread-count', (s) => s.reply(200, {'count': 3}));

    expect(await repository.loadUnreadCount(), 3);
  });

  test('marque un fil lu sans rien attendre en retour', () async {
    dioAdapter.onPost('/support/tickets/t1/read', (s) => s.reply(204, null));

    await expectLater(repository.markRead('t1'), completes);
  });

  test('rend la cle distante apres un upload', () async {
    dioAdapter.onPost('/support/attachments',
        (s) => s.reply(200, {'key': 'support/u1/1_a.jpg', 'url': 'https://signed'}));

    expect(await repository.uploadAttachment('/tmp/a.jpg'), 'support/u1/1_a.jpg');
  });

  test('lit les pieces jointes et le compteur dans le detail d un ticket', () async {
    dioAdapter.onGet('/support/tickets/t1', (s) => s.reply(200, {
          'id': 't1', 'category': 'PAYMENT', 'subject': 'Sujet', 'status': 'WAITING_USER',
          'createdAt': '2026-09-01T10:00:00', 'lastMessageAt': '2026-09-02T08:30:00',
          'resolvedAt': null, 'unreadCount': 2,
          'messages': [
            {'id': 'm1', 'authorType': 'ADMIN', 'content': '', 'createdAt': '2026-09-02T08:30:00',
             'attachments': [{'id': 'a1', 'url': 'https://signed', 'contentType': 'image/jpeg'}]},
          ],
        }));

    final ticket = await repository.loadTicket('t1');

    expect(ticket.unreadCount, 2);
    expect(ticket.messages.single.attachments.single.url, 'https://signed');
  });
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
flutter test test/features/support/support_repository_test.dart
```

Attendu : ÉCHEC de compilation, `loadUnreadCount`, `markRead`, `uploadAttachment`, `unreadCount` et `attachments` n'existent pas.

- [ ] **Étape 3 : écrire le modèle de pièce jointe**

Créer `lib/features/support/data/support_attachment.dart` :

```dart
/// Image jointe à un message support. L'URL est présignée par le backend et
/// expire au bout d'une heure : ne jamais la mettre en cache sur disque.
class SupportAttachment {
  const SupportAttachment({
    required this.id,
    required this.url,
    required this.contentType,
  });

  final String id;
  final String url;
  final String contentType;

  factory SupportAttachment.fromJson(Map<String, dynamic> json) {
    return SupportAttachment(
      id: json['id'] as String,
      url: json['url'] as String,
      contentType: json['contentType'] as String? ?? 'image/jpeg',
    );
  }
}

enum SupportUploadStatus { uploading, ready, failed }

/// État local d'une image en cours d'envoi, avant qu'elle ne rejoigne un
/// message. `remoteKey` n'est rempli qu'une fois l'upload terminé.
class SupportAttachmentUpload {
  const SupportAttachmentUpload({
    required this.localId,
    required this.localPath,
    required this.status,
    this.remoteKey,
  });

  final String localId;
  final String localPath;
  final SupportUploadStatus status;
  final String? remoteKey;

  SupportAttachmentUpload copyWith({
    SupportUploadStatus? status,
    String? remoteKey,
  }) {
    return SupportAttachmentUpload(
      localId: localId,
      localPath: localPath,
      status: status ?? this.status,
      remoteKey: remoteKey ?? this.remoteKey,
    );
  }
}
```

- [ ] **Étape 4 : élargir les modèles existants**

Dans `support_models.dart`, `SupportMessage` gagne `final List<SupportAttachment> attachments` (par défaut `const []`) lu depuis `json['attachments']`, et `SupportTicket` gagne `final int unreadCount` lu depuis `json['unreadCount'] as int? ?? 0`.

- [ ] **Étape 5 : élargir le dépôt**

Dans `support_repository.dart`, ajouter :

```dart
  Future<void> markRead(String ticketId) async {
    await _api.dio.post('/support/tickets/$ticketId/read');
  }

  Future<int> loadUnreadCount() async {
    final response = await _api.dio.get('/support/unread-count');
    final data = response.data as Map<String, dynamic>? ?? const {};
    return (data['count'] as num?)?.toInt() ?? 0;
  }

  Future<String> uploadAttachment(String filePath) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
    });
    final response = await _api.dio.post('/support/attachments', data: form);
    return (response.data as Map<String, dynamic>)['key'] as String;
  }
```

et ajouter `attachmentKeys` aux corps de `createTicket` et `sendMessage`.

- [ ] **Étape 6 : relancer le test, vérifier qu'il passe**

```bash
flutter test test/features/support/support_repository_test.dart
```

Attendu : tous verts.

- [ ] **Étape 7 : commit**

```bash
git add lib/features/support/data test/features/support/support_repository_test.dart
git commit -m "feat(support): modeles et acces reseau des pieces jointes et du non-lu"
```

---

### Task 9 : BLoC — lecture, compteur, upload

**Files:**
- Modify: `lib/features/support/bloc/support_bloc.dart`, `support_event.dart`, `support_state.dart`
- Create: `lib/features/support/bloc/support_unread_cubit.dart`
- Modify: `lib/core/di/injection.dart`
- Modify: `lib/core/services/analytics_events.dart`
- Test: `test/features/support/support_bloc_test.dart`

**Interfaces:**
- Consomme : `SupportRepository` (Task 8).
- Produit :
  - Events `SupportTicketReadRequested(String ticketId)`, `SupportAttachmentPickRequested(String localPath)`, `SupportAttachmentRemoved(String localId)`
  - `SupportState` gagne `final List<SupportAttachmentUpload> pendingAttachments` et `bool get canSend`
  - `SupportUnreadCubit extends Cubit<int>` avec `refresh() : Future<void>` et `decrementBy(int)`
  - `AnalyticsEvents.supportAttachmentAdded = 'support_attachment_added'`

- [ ] **Étape 1 : écrire les tests, qui doivent échouer**

Ajouter à `test/features/support/support_bloc_test.dart` :

```dart
  blocTest<SupportBloc, SupportState>(
    'marque le fil lu a l ouverture du detail',
    build: () {
      when(() => repository.markRead('t1')).thenAnswer((_) async {});
      when(() => repository.loadTicket('t1')).thenAnswer((_) async => ticket);
      return SupportBloc(repository, analytics);
    },
    act: (bloc) => bloc.add(const SupportTicketDetailRequested('t1')),
    verify: (_) => verify(() => repository.markRead('t1')).called(1),
  );

  blocTest<SupportBloc, SupportState>(
    'passe une image de uploading a ready puis autorise l envoi',
    build: () {
      when(() => repository.uploadAttachment('/tmp/a.jpg'))
          .thenAnswer((_) async => 'support/u1/1_a.jpg');
      return SupportBloc(repository, analytics);
    },
    act: (bloc) => bloc.add(const SupportAttachmentPickRequested('/tmp/a.jpg')),
    expect: () => [
      isA<SupportState>().having((s) => s.pendingAttachments.single.status,
          'status', SupportUploadStatus.uploading),
      isA<SupportState>()
          .having((s) => s.pendingAttachments.single.status, 'status', SupportUploadStatus.ready)
          .having((s) => s.canSend, 'canSend', true),
    ],
  );

  blocTest<SupportBloc, SupportState>(
    'bloque l envoi tant qu un upload est en cours',
    build: () {
      when(() => repository.uploadAttachment(any()))
          .thenAnswer((_) => Future.delayed(const Duration(seconds: 1), () => 'k'));
      return SupportBloc(repository, analytics);
    },
    act: (bloc) => bloc.add(const SupportAttachmentPickRequested('/tmp/a.jpg')),
    expect: () => [isA<SupportState>().having((s) => s.canSend, 'canSend', false)],
  );

  blocTest<SupportBloc, SupportState>(
    'refuse d envoyer sur un ticket resolu sans appeler le reseau',
    build: () => SupportBloc(repository, analytics),
    seed: () => SupportState.initial().copyWith(ticket: resolvedTicket),
    act: (bloc) => bloc.add(const SupportMessageSendRequested('t1', 'Bonjour')),
    verify: (_) => verifyNever(() => repository.sendMessage(any(), any(), any())),
  );
```

Et un test dédié au cubit :

```dart
  test('le compteur decroit apres lecture sans attendre le serveur', () async {
    when(() => repository.loadUnreadCount()).thenAnswer((_) async => 3);
    final cubit = SupportUnreadCubit(repository);

    await cubit.refresh();
    expect(cubit.state, 3);

    cubit.decrementBy(3);
    expect(cubit.state, 0);
  });
```

- [ ] **Étape 2 : lancer les tests, vérifier qu'ils échouent**

```bash
flutter test test/features/support/
```

Attendu : ÉCHEC de compilation.

- [ ] **Étape 3 : déclarer l'event analytics**

Dans `lib/core/services/analytics_events.dart` :

```dart
  static const supportAttachmentAdded = 'support_attachment_added';
```

Aucune propriété : ni le chemin du fichier, ni sa taille, ni le contenu du message ne doivent partir dans l'analytics.

- [ ] **Étape 4 : implémenter le BLoC et le cubit**

Dans `support_bloc.dart`, le handler de détail appelle `markRead` avant `loadTicket` et ignore silencieusement l'échec du marquage (ne pas empêcher la lecture du fil parce qu'un accusé n'est pas passé). Le handler d'upload émet d'abord l'état `uploading`, puis `ready` ou `failed`.

`canSend` s'écrit dans `support_state.dart` :

```dart
  /// Envoi possible s'il y a du texte ou au moins une image prête, et
  /// qu'aucun upload n'est encore en cours. Une image en échec ne bloque
  /// pas : elle est simplement retirée du message.
  bool get canSend {
    final uploading = pendingAttachments
        .any((a) => a.status == SupportUploadStatus.uploading);
    if (uploading) return false;
    return draftHasText ||
        pendingAttachments.any((a) => a.status == SupportUploadStatus.ready);
  }
```

Créer `support_unread_cubit.dart` avec `refresh()` (avale les erreurs réseau et garde la valeur courante) et `decrementBy(int)` borné à zéro.

Enregistrer le cubit dans `injection.dart` :

```dart
  getIt.registerLazySingleton(() => SupportUnreadCubit(getIt()));
```

Singleton et non factory : le badge de l'onglet et l'écran de détail doivent partager la même instance, sinon la pastille ne s'éteint pas à la lecture.

- [ ] **Étape 5 : relancer les tests, vérifier qu'ils passent**

```bash
flutter test test/features/support/
```

- [ ] **Étape 6 : commit**

```bash
git add lib/features/support/bloc lib/core/di/injection.dart lib/core/services/analytics_events.dart test/features/support
git commit -m "feat(support): lecture, compteur de non-lus et upload dans le bloc"
```

---

### Task 10 : Ligne épinglée dans Messages et badge agrégé

**Files:**
- Create: `lib/features/support/presentation/widgets/support_conversation_tile.dart`
- Modify: `lib/app/main_shell.dart:438-451`
- Modify: l'écran de liste des conversations (onglet Messages)
- Modify: `lib/features/matching/presentation/widgets/activites_menu_sheet.dart:184`
- Test: `test/features/support/support_conversation_tile_test.dart`

**Interfaces:**
- Consomme : `SupportUnreadCubit` (Task 9).
- Produit : `SupportConversationTile({required int unreadCount, required String preview})`, qui pousse vers `/support`.

- [ ] **Étape 1 : écrire le test, qui doit échouer**

```dart
  testWidgets('affiche la ligne meme sans aucun ticket, avec une invitation', (tester) async {
    await tester.pumpWidget(wrap(const SupportConversationTile(unreadCount: 0, preview: '')));

    expect(find.text('Support Yadony'), findsOneWidget);
    expect(find.textContaining('Une question'), findsOneWidget);
  });

  testWidgets('affiche la pastille quand il y a des non-lus', (tester) async {
    await tester.pumpWidget(wrap(const SupportConversationTile(unreadCount: 2, preview: 'Bonjour')));

    expect(find.text('2'), findsOneWidget);
  });

  testWidgets('ouvre /support au tap', (tester) async {
    await tester.pumpWidget(wrapWithRouter(const SupportConversationTile(unreadCount: 0, preview: '')));
    await tester.tap(find.byType(SupportConversationTile));
    await tester.pumpAndSettle();

    expect(currentRoute, '/support');
  });
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
flutter test test/features/support/support_conversation_tile_test.dart
```

- [ ] **Étape 3 : écrire le widget**

Créer `support_conversation_tile.dart`. Contraintes de style à respecter : `GoogleFonts.plusJakartaSans`, cible tactile ≥ 44 pt, `kBackground` jamais blanc pur, rayon de carte 16. Le libellé affiché est **« Support Yadony »**. L'aperçu par défaut, quand aucun ticket n'existe, est « Une question ? Notre équipe vous répond ici. » — sans tiret cadratin.

Le widget est visuellement distinct des conversations entre membres : logo à la place de l'avatar, libellé non modifiable.

- [ ] **Étape 4 : injecter la ligne en tête de liste**

Dans l'écran de liste des conversations, insérer `SupportConversationTile` avant l'itération des conversations Firestore. Elle n'entre pas dans le `ListView.builder` des conversations : c'est un élément fixe placé au-dessus, non déplaçable et non filtrable.

- [ ] **Étape 5 : additionner les deux compteurs sur le badge**

Dans `main_shell.dart`, le `StreamBuilder<int>` des lignes 438-451 devient un `BlocBuilder<SupportUnreadCubit, int>` imbriqué :

```dart
                                  return StreamBuilder<int>(
                                    stream: getIt<FirestoreChatRepository>()
                                        .totalUnreadStream(uid),
                                    builder: (context, snapshot) {
                                      return BlocBuilder<SupportUnreadCubit, int>(
                                        bloc: getIt<SupportUnreadCubit>(),
                                        builder: (context, supportUnread) {
                                          return DonyNavItem(
                                            iconAsset: 'message-circle',
                                            label: 'Messages',
                                            index: 3,
                                            currentIndex: currentIndex,
                                            onTap: () => onTap(3),
                                            badgeCount:
                                                (snapshot.data ?? 0) + supportUnread,
                                          );
                                        },
                                      );
                                    },
                                  );
```

Les deux sources restent séparées : pas de couche d'abstraction commune, juste une addition. Appeler `getIt<SupportUnreadCubit>().refresh()` à l'ouverture de l'onglet et à la réception d'un push de type `SUPPORT_MESSAGE`.

- [ ] **Étape 6 : corriger l'entrée du menu Activités**

`activites_menu_sheet.dart:184` pointe « Aide et support » vers `/profile/help/faq`. Conserver cette entrée pour la FAQ, et vérifier qu'aucun libellé ne laisse croire qu'elle mène aux tickets. Le chemin vers les demandes est désormais l'onglet Messages.

- [ ] **Étape 7 : router le deep link du push**

Le push porte `type: SUPPORT_MESSAGE` et `ticketId`. Le gestionnaire de notifications doit router vers `/support/tickets/{ticketId}`, route déjà déclarée à `router.dart:1622`.

- [ ] **Étape 8 : relancer les tests**

```bash
flutter test test/features/support/
flutter analyze
```

Attendu : 0 échec, aucun avertissement. Lancer `flutter analyze` sur tout le projet, pas sur un répertoire ciblé : un analyze partiel laisse passer des lints que la CI voit.

- [ ] **Étape 9 : commit**

```bash
git add lib/features/support/presentation lib/app/main_shell.dart test/features/support
git commit -m "feat(support): ligne epinglee Support Yadony et badge agrege"
```

---

### Task 11 : Envoi et rendu des images dans le fil

**Files:**
- Create: `lib/features/support/presentation/widgets/support_attachment_picker.dart`
- Modify: `lib/features/support/presentation/screens/support_ticket_detail_screen.dart`
- Modify: `dony_app/CLAUDE.md` (table des events)
- Test: `test/features/support/support_ticket_detail_screen_test.dart`

**Interfaces:**
- Consomme : `SupportState.pendingAttachments`, `SupportState.canSend` (Task 9), `SupportAttachment` (Task 8).

- [ ] **Étape 1 : écrire les tests, qui doivent échouer**

```dart
  testWidgets('le bouton d envoi reste inerte pendant un upload', (tester) async {
    await pumpDetail(tester, state: stateWithUploading);
    expect(tester.widget<DonyButton>(find.byType(DonyButton)).onPressed, isNull);
  });

  testWidgets('le bouton s active avec une image prete et aucun texte', (tester) async {
    await pumpDetail(tester, state: stateWithReadyImageNoText);
    expect(tester.widget<DonyButton>(find.byType(DonyButton)).onPressed, isNotNull);
  });

  testWidgets('un ticket resolu n offre ni champ ni trombone', (tester) async {
    await pumpDetail(tester, state: resolvedState);
    expect(find.byType(TextField), findsNothing);
    expect(find.byType(SupportAttachmentPicker), findsNothing);
  });

  testWidgets('affiche les images d un message en grille', (tester) async {
    await pumpDetail(tester, state: stateWithTwoAttachments);
    expect(find.byType(Image), findsNWidgets(2));
  });
```

- [ ] **Étape 2 : lancer les tests, vérifier qu'ils échouent**

```bash
flutter test test/features/support/support_ticket_detail_screen_test.dart
```

- [ ] **Étape 3 : écrire le sélecteur**

Créer `support_attachment_picker.dart` : un bouton trombone et une rangée de vignettes au-dessus du champ de saisie, chacune portant son état (indicateur de progression, coche, ou croix rouge avec possibilité de retrait). Réutiliser le sélecteur et la compression déjà en place pour les photos de demande (`package_request_photo_upload.dart`) plutôt que d'en écrire un autre. Plafond de 4 : le trombone se désactive au quatrième.

- [ ] **Étape 4 : rendre les images reçues**

Dans la bulle de message, afficher `message.attachments` en grille. Un tap ouvre la visionneuse plein écran déjà utilisée ailleurs dans l'app. Utiliser `CachedNetworkImage` avec prudence : l'URL présignée expire en une heure, donc ne pas la conserver comme clé de cache durable.

- [ ] **Étape 5 : verrouiller le ticket résolu**

Ni champ de saisie ni trombone sur un ticket `RESOLVED`. Le refus est appliqué avant tout appel réseau ; le backend le refait de toute façon en 422.

- [ ] **Étape 6 : documenter l'event analytics**

Ajouter la ligne à la table de `dony_app/CLAUDE.md` :

```
| `support_attachment_added` | SupportBloc._onAttachmentPickRequested — image jointe uploadée avec succès dans un fil support. Aucune propriété : ni chemin, ni taille, ni contenu ne partent dans l'analytics |
```

- [ ] **Étape 7 : suite complète et couverture**

```bash
flutter test --coverage
flutter analyze
```

Attendu : 0 échec, couverture ≥ 90 %.

- [ ] **Étape 8 : commit**

```bash
git add lib/features/support test/features/support CLAUDE.md
git commit -m "feat(support): envoi et rendu des images dans le fil"
```

---

# Lot 3 — Back-office admin

> Peut avancer en parallèle du lot 2, une fois le lot 1 déployé.
>
> Rappel : le seuil de couverture Vitest est **global**. Tout composant Vue ajouté ici doit être testé dans le même lot, sinon le build entier échoue même si le reste est vert.

### Task 12 : Affichage des images dans le fil

**Files:**
- Create: `app/features/support/components/SupportAttachmentGrid.vue`
- Modify: `app/features/support/types/index.ts`
- Modify: `app/features/support/components/SupportTicketThread.vue:98-121`
- Test: `tests/unit/features/support/SupportAttachmentGrid.spec.ts`

**Interfaces:**
- Consomme : `SupportAttachmentResponse` du backend (Task 4).
- Produit :
  - type `AdminSupportAttachment { id: string; url: string; contentType: string }`
  - `AdminSupportMessage` gagne `attachments?: AdminSupportAttachment[]`
  - composant `SupportAttachmentGrid` avec `defineProps<{ attachments: AdminSupportAttachment[] }>()`

- [ ] **Étape 1 : écrire le test, qui doit échouer**

Créer `tests/unit/features/support/SupportAttachmentGrid.spec.ts` :

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SupportAttachmentGrid from '@/features/support/components/SupportAttachmentGrid.vue'

const attachments = [
  { id: 'a1', url: 'https://signed/1', contentType: 'image/jpeg' },
  { id: 'a2', url: 'https://signed/2', contentType: 'image/png' },
]

describe('SupportAttachmentGrid', () => {
  it('rend une vignette par pièce jointe', () => {
    const w = mount(SupportAttachmentGrid, { props: { attachments } })
    expect(w.findAll('img')).toHaveLength(2)
    expect(w.findAll('img')[0].attributes('src')).toBe('https://signed/1')
  })

  it('ne rend rien quand il n’y a aucune pièce jointe', () => {
    const w = mount(SupportAttachmentGrid, { props: { attachments: [] } })
    expect(w.findAll('img')).toHaveLength(0)
  })

  it('émet open avec l’URL de la vignette cliquée', async () => {
    const w = mount(SupportAttachmentGrid, { props: { attachments } })
    await w.findAll('img')[1].trigger('click')
    expect(w.emitted('open')![0]).toEqual(['https://signed/2'])
  })

  it('donne un texte alternatif à chaque image', () => {
    const w = mount(SupportAttachmentGrid, { props: { attachments } })
    expect(w.findAll('img')[0].attributes('alt')).toBeTruthy()
  })
})
```

- [ ] **Étape 2 : lancer le test, vérifier qu'il échoue**

```bash
pnpm vitest run tests/unit/features/support/SupportAttachmentGrid.spec.ts
```

- [ ] **Étape 3 : écrire le composant et le type**

Créer `SupportAttachmentGrid.vue`, ajouter `AdminSupportAttachment` à `types/index.ts`, et l'insérer dans la bulle de `SupportTicketThread.vue` sous le paragraphe de contenu :

```vue
        <p v-if="m.content" class="whitespace-pre-wrap">{{ m.content }}</p>
        <SupportAttachmentGrid
          v-if="m.attachments?.length"
          :attachments="m.attachments"
          class="mt-2"
          @open="openViewer"
        />
```

Noter le `v-if="m.content"` : un message peut désormais n'être qu'une image, et un paragraphe vide laisserait un blanc.

- [ ] **Étape 4 : relancer le test, vérifier qu'il passe**

```bash
pnpm vitest run tests/unit/features/support/
```

- [ ] **Étape 5 : commit**

```bash
git add app/features/support tests/unit/features/support
git commit -m "feat(support): affichage des images dans le fil admin"
```

---

### Task 13 : Envoi d'images depuis le back-office

**Files:**
- Create: `app/features/support/components/SupportAttachmentUploader.vue`
- Modify: le service support (`app/features/support/...`), `SupportTicketThread.vue:126-144`
- Test: `tests/unit/features/support/SupportAttachmentUploader.spec.ts`
- Test: `tests/unit/features/support/SupportTicketThread.spec.ts` (compléter)

**Interfaces:**
- Consomme : `POST /admin/support/tickets/attachments` (Task 5).
- Produit : le composant `SupportAttachmentUploader` émettant `change: [keys: string[]]` et `busy: [value: boolean]`. `SupportTicketThread` émet désormais `reply: [id: string, content: string, attachmentKeys: string[]]`.

- [ ] **Étape 1 : écrire les tests, qui doivent échouer**

Dans `SupportAttachmentUploader.spec.ts` : rendu du bouton, désactivation au quatrième fichier, émission de `busy` pendant l'upload, émission de `change` avec les clés obtenues, gestion d'un upload en échec (la vignette est retirable, l'envoi reste possible).

Compléter `SupportTicketThread.spec.ts` avec :

```ts
    it('émet reply avec les clés des images jointes', async () => {
      const w = mountThread(mine)
      await w.findComponent({ name: 'SupportAttachmentUploader' })
        .vm.$emit('change', ['support/admin/u1/1_a.jpg'])
      await w.find('textarea').setValue('Voici la capture')
      await w.findAll('button').find(b => b.text() === 'Répondre')!.trigger('click')

      expect(w.emitted('reply')![0]).toEqual([
        't1', 'Voici la capture', ['support/admin/u1/1_a.jpg'],
      ])
    })

    it('autorise une réponse faite d’une image sans texte', async () => {
      const w = mountThread(mine)
      await w.findComponent({ name: 'SupportAttachmentUploader' })
        .vm.$emit('change', ['support/admin/u1/1_a.jpg'])
      expect(w.findAll('button').find(b => b.text() === 'Répondre')!
        .attributes('disabled')).toBeUndefined()
    })

    it('masque l’uploader à un rôle sans SUPPORT_TICKET_MANAGE', () => {
      seedAuth('SUPPORT', { SUPPORT_TICKET_MANAGE: false })
      const w = mountThread(mine)
      expect(w.findComponent({ name: 'SupportAttachmentUploader' }).exists()).toBe(false)
    })

    it('n’offre pas d’uploader sur un ticket résolu', () => {
      const resolved = { ...mine, status: 'RESOLVED', resolvedAt: '2026-09-03T12:00:00Z' }
      const w = mountThread(resolved)
      expect(w.findComponent({ name: 'SupportAttachmentUploader' }).exists()).toBe(false)
    })
```

- [ ] **Étape 2 : lancer les tests, vérifier qu'ils échouent**

```bash
pnpm vitest run tests/unit/features/support/
```

- [ ] **Étape 3 : écrire le composant et brancher le fil**

L'uploader vit dans le même bloc `v-else-if="canManage && isMine"` que le champ de réponse : il hérite ainsi automatiquement du gating par permission, par assignation et par statut résolu, sans condition dupliquée.

La condition d'envoi de `SupportTicketThread.vue` devient :

```js
const canSubmit = computed(() =>
  !props.acting && !uploading.value &&
  (draft.value.trim().length > 0 || attachmentKeys.value.length > 0))
```

et `sendReply` transmet les clés puis les vide en même temps que le brouillon.

- [ ] **Étape 4 : relancer les tests et le typecheck**

```bash
pnpm vitest run
pnpm typecheck
pnpm lint
```

Les trois sont nécessaires. Le typecheck attrape ce que les tests ne voient pas : c'est lui, et lui seul, qui avait révélé que la pagination du support était inerte au lot précédent.

- [ ] **Étape 5 : vérifier la couverture globale**

```bash
pnpm vitest run --coverage
```

Attendu : branches ≥ 90 %. Si le seuil n'est pas atteint, ajouter des tests aux composants ajoutés ici avant de commiter.

- [ ] **Étape 6 : commit**

```bash
git add app/features/support tests/unit/features/support
git commit -m "feat(support): envoi d'images depuis le back-office"
```

---

## Ordre de livraison

1. **Prérequis** : débloquer le déploiement en recette du lot support déjà fusionné, et valider le parcours actuel sur un appareil réel. Rien de ce plan n'a de sens tant que le code existant n'a jamais tourné hors des tests.
2. **Lot 1** (Tasks 1 à 7) — fusionné et déployé en premier, l'app et le back-office dépendent tous deux de ses endpoints.
3. **Lots 2 et 3** en parallèle une fois l'API disponible en recette.

Chaque lot vit dans son propre dépôt, donc une pull request par dépôt.

## Vérifications finales, par dépôt

**Backend :** `./mvnw test` à 0 rouge, `./mvnw jacoco:report` ≥ 90 %, et une relecture du numéro de migration contre `origin/main` juste avant de fusionner.

**Flutter :** `flutter test --coverage` ≥ 90 %, `flutter analyze` sans avertissement sur tout le projet, `dart format lib test` appliqué (un défaut de format fait échouer le job Analyze & Format).

**Admin :** `pnpm vitest run --coverage`, `pnpm typecheck` et `pnpm lint`, tous les trois verts.

**Et sur chaque PR, avant de conclure :** `gh pr view <n> --json mergeable,mergeStateStatus,statusCheckRollup`. Un run local ne voit ni le typecheck, ni les seuils globaux, ni l'état de `main`. Au lot précédent, trois défauts sur cinq auraient survécu à la fusion parce que « tout passe en local » avait été pris pour « les PR sont vertes ».

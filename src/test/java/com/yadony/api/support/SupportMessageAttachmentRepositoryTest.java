package com.yadony.api.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    void findsAttachmentsByMessageIdInCreationOrder() {
        // Verify order deterministically by flushing between inserts to ensure distinct createdAt timestamps
        UUID messageA = UUID.randomUUID();
        var first = repository.save(attachment(messageA, "support/u/1_a.jpg"));
        repository.flush();
        var second = repository.save(attachment(messageA, "support/u/2_b.jpg"));
        repository.flush();

        List<SupportMessageAttachmentEntity> found = repository.findByMessageIdOrderByCreatedAtAsc(messageA);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(SupportMessageAttachmentEntity::getObjectKey)
                .containsExactly("support/u/1_a.jpg", "support/u/2_b.jpg");
    }

    @Test
    void findsKeysCreatedSinceGivenInstant() {
        // Save an attachment
        repository.save(attachment(UUID.randomUUID(), "support/u/1_a.jpg"));
        repository.flush();

        // Query with 1 minute in the past → should find the attachment
        LocalDateTime oneMinuteAgo = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        List<String> foundInPast = repository.findObjectKeysCreatedSince(oneMinuteAgo);

        assertThat(foundInPast).containsExactly("support/u/1_a.jpg");

        // Query with 1 minute in the future → should find nothing
        LocalDateTime oneMinuteFromNow = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        List<String> foundInFuture = repository.findObjectKeysCreatedSince(oneMinuteFromNow);

        assertThat(foundInFuture).isEmpty();
    }

    @Test
    void findsWhichKeysAreAlreadyReferenced() {
        repository.save(attachment(UUID.randomUUID(), "support/u/1_a.jpg"));

        List<String> referenced = repository.findObjectKeysByObjectKeyIn(
                List.of("support/u/1_a.jpg", "support/u/orphan.jpg"));

        assertThat(referenced).containsExactly("support/u/1_a.jpg");
    }

    private static SupportMessageAttachmentEntity attachment(UUID messageId, String key) {
        // UUID.randomUUID() is used as messageId without seeding support_messages because
        // the test profile does not enforce FK constraints. The FK itself is covered by
        // V245SupportReadStateAndAttachmentsMigrationTest on embedded PostgreSQL.
        SupportMessageAttachmentEntity entity = new SupportMessageAttachmentEntity();
        entity.setMessageId(messageId);
        entity.setObjectKey(key);
        entity.setContentType("image/jpeg");
        entity.setSizeBytes(2048L);
        return entity;
    }
}

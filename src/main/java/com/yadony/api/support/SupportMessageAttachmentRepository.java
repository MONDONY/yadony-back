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

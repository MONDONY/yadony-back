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

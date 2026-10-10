package com.yadony.api.common.media;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "media_uploads")
public class MediaUploadEntity extends BaseEntity {

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private MediaPurpose purpose;

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    protected MediaUploadEntity() {
    }

    public MediaUploadEntity(String objectKey, UUID ownerId, MediaPurpose purpose, UUID bidId, long sizeBytes) {
        this.objectKey = objectKey;
        this.ownerId = ownerId;
        this.purpose = purpose;
        this.bidId = bidId;
        this.sizeBytes = sizeBytes;
    }

    public String getObjectKey() { return objectKey; }
    public UUID getOwnerId() { return ownerId; }
    public MediaPurpose getPurpose() { return purpose; }
    public UUID getBidId() { return bidId; }
    public long getSizeBytes() { return sizeBytes; }
}

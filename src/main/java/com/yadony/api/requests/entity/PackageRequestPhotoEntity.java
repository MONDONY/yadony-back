package com.yadony.api.requests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Une photo de colis jointe à une demande d'envoi. Source des photos : elles sont
 * copiées vers bids/ à la matérialisation du bid. Pas de soft-delete — n'étend pas BaseEntity.
 */
@Entity
@Table(name = "package_request_photos")
@org.hibernate.annotations.SQLRestriction("purged_at IS NULL")
public class PackageRequestPhotoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "package_request_id", nullable = false)
    private UUID packageRequestId;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    protected PackageRequestPhotoEntity() {
    }

    public PackageRequestPhotoEntity(UUID packageRequestId, String objectKey, int position) {
        this.packageRequestId = packageRequestId;
        this.objectKey = objectKey;
        this.position = position;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() { return id; }
    public UUID getPackageRequestId() { return packageRequestId; }
    public String getObjectKey() { return objectKey; }
    public int getPosition() { return position; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPurgedAt() { return purgedAt; }
    public void markPurged(LocalDateTime at) { this.purgedAt = at; }
}

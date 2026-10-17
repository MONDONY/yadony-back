package com.yadony.api.gppro;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/** Justificatif d'une demande GP Pro. */
@Entity
@Table(name = "gp_pro_documents")
@Where(clause = "deleted_at IS NULL")
public class GpProDocumentEntity extends BaseEntity {

    /** Nul tant que le document n'est pas rattaché à une demande. */
    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private GpProDocumentType docType;

    /** Nul une fois le fichier purgé. */
    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID applicationId) { this.applicationId = applicationId; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public GpProDocumentType getDocType() { return docType; }
    public void setDocType(GpProDocumentType docType) { this.docType = docType; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public LocalDateTime getPurgedAt() { return purgedAt; }
    public void setPurgedAt(LocalDateTime purgedAt) { this.purgedAt = purgedAt; }
}

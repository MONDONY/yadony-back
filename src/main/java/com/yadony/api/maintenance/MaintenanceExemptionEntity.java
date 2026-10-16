package com.yadony.api.maintenance;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

/** Compte autorisé à utiliser l'application pendant la maintenance. */
@Entity
@Table(name = "maintenance_exemptions")
@Where(clause = "deleted_at IS NULL")
public class MaintenanceExemptionEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "note", length = 120)
    private String note;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}

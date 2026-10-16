package com.yadony.api.maintenance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ligne unique (id = 1) de l'état du mode maintenance. Pas de {@code BaseEntity} : cette
 * ligne n'est jamais créée ni supprimée par l'application, seulement mise à jour.
 */
@Entity
@Table(name = "maintenance_state")
public class MaintenanceStateEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Short id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "message", length = 300)
    private String message;

    /** UTC. */
    @Column(name = "estimated_end_at")
    private LocalDateTime estimatedEndAt;

    /** UTC ; posé au passage de faux à vrai, effacé à la désactivation. */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Short getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getEstimatedEndAt() { return estimatedEndAt; }
    public void setEstimatedEndAt(LocalDateTime estimatedEndAt) { this.estimatedEndAt = estimatedEndAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

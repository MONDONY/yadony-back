package com.yadony.api.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Story 6.5 — Operator alert record.
 * Does NOT extend BaseEntity: the table has no updated_at / deleted_at columns.
 * Alerts are never soft-deleted; they are resolved (resolved=true) instead.
 */
@Entity
@Table(name = "admin_alerts")
public class AdminAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Short discriminator, e.g. "ESCROW_J48_TIMEOUT". */
    @Column(name = "type", nullable = false, length = 60)
    private String type;

    /**
     * Free-form JSON stored in the JSONB column. Callers build the JSON string
     * themselves (e.g. with String.format or Jackson).
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} est OBLIGATOIRE : sans lui, Hibernate 6
     * envoie un {@code varchar} et Postgres refuse l'INSERT ("column payload is of
     * type jsonb but expression is of type character varying") — toutes les alertes
     * DB (escrow J+48, échéances de retour) échouaient en silence dans les
     * schedulers (staging/prod, constaté le 2026-09-02).
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity = "INFO";

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters & setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}

package com.yadony.api.kyc.erasure;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Session Didit à effacer chez Didit après la suppression d'un compte (V345). Écrite dans la
 * transaction de la finalisation ; voir {@link DiditSessionErasureService}.
 */
@Entity
@Table(name = "didit_session_deletions", schema = "kyc_schema")
@Where(clause = "deleted_at IS NULL")
public class DiditSessionDeletionEntity extends BaseEntity {

    public enum Status { PENDING, DONE, FAILED }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false, unique = true, length = 255, updatable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected DiditSessionDeletionEntity() {
    }

    public DiditSessionDeletionEntity(UUID userId, String sessionId, LocalDateTime nextAttemptAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.nextAttemptAt = nextAttemptAt;
    }

    public UUID getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public LocalDateTime getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(LocalDateTime lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Integer getLastHttpStatus() { return lastHttpStatus; }
    public void setLastHttpStatus(Integer lastHttpStatus) { this.lastHttpStatus = lastHttpStatus; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}

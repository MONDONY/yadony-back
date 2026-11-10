package com.yadony.api.dac7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne du registre des diligences DAC7 ({@code dac7_due_diligence_log}, V375). En ajout seul comme
 * {@code audit_log} : {@link Immutable} côté Hibernate, trigger côté PostgreSQL, et aucun setter après création.
 * N'étend pas {@code BaseEntity} : un registre immuable n'a ni mise à jour ni suppression, même logique.
 */
@Entity
@Immutable
@Table(name = "dac7_due_diligence_log")
public class Dac7DueDiligenceLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private Dac7DueDiligenceAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private Dac7DueDiligenceChannel channel;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Dac7DueDiligenceLogEntity() {
        // JPA
    }

    Dac7DueDiligenceLogEntity(UUID userId, Dac7DueDiligenceAction action, Dac7DueDiligenceChannel channel,
                              UUID actorId, String note) {
        this.userId = userId;
        this.action = action;
        this.channel = channel;
        this.actorId = actorId;
        this.note = note;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public Dac7DueDiligenceAction getAction() { return action; }
    public Dac7DueDiligenceChannel getChannel() { return channel; }
    public UUID getActorId() { return actorId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}

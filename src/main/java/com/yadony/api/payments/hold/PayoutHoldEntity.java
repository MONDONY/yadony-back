package com.yadony.api.payments.hold;

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
 * Gel des versements d'un voyageur, un enregistrement par motif (V270).
 *
 * <p>Un gel n'est jamais supprime : sa levee pose {@code released_at}. Au plus un gel actif par
 * couple (utilisateur, motif), garanti en base par un index unique partiel. Les deux motifs
 * peuvent coexister : le voyageur reste gele tant qu'un gel actif subsiste.
 */
@Entity
@Table(name = "payout_holds")
@Where(clause = "deleted_at IS NULL")
public class PayoutHoldEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30, updatable = false)
    private PayoutHoldReason reason;

    @Column(name = "held_since", nullable = false, updatable = false)
    private LocalDateTime heldSince;

    /** Administrateur a l'origine du gel ; {@code null} pour le rattrapage V270. */
    @Column(name = "held_by", updatable = false)
    private UUID heldBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    protected PayoutHoldEntity() {
    }

    public PayoutHoldEntity(UUID userId, PayoutHoldReason reason, LocalDateTime heldSince, UUID heldBy) {
        this.userId = userId;
        this.reason = reason;
        this.heldSince = heldSince;
        this.heldBy = heldBy;
    }

    public boolean isActive() {
        return releasedAt == null;
    }

    public void release(LocalDateTime at, UUID by) {
        this.releasedAt = at;
        this.releasedBy = by;
    }

    public UUID getUserId() { return userId; }
    public PayoutHoldReason getReason() { return reason; }
    public LocalDateTime getHeldSince() { return heldSince; }
    public UUID getHeldBy() { return heldBy; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public UUID getReleasedBy() { return releasedBy; }
}

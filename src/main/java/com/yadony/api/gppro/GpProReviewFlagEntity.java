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

/** Titulaire GP Pro à réexaminer, pour un motif donné. */
@Entity
@Table(name = "gp_pro_review_flags")
@Where(clause = "deleted_at IS NULL")
public class GpProReviewFlagEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private GpProReviewReason reason;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    /** La condition qui avait déclenché le signalement a cessé. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "dismissed_by")
    private UUID dismissedBy;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public GpProReviewReason getReason() { return reason; }
    public void setReason(GpProReviewReason reason) { this.reason = reason; }

    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(LocalDateTime dismissedAt) { this.dismissedAt = dismissedAt; }

    public UUID getDismissedBy() { return dismissedBy; }
    public void setDismissedBy(UUID dismissedBy) { this.dismissedBy = dismissedBy; }
}

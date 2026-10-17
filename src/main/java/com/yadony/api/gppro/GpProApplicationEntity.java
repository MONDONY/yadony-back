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

/** Demande du badge GP Pro. */
@Entity
@Table(name = "gp_pro_applications")
@Where(clause = "deleted_at IS NULL")
public class GpProApplicationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GpProApplicationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private GpProApplicationSource source;

    /** Nul pour une demande créée par l'admin. */
    @Enumerated(EnumType.STRING)
    @Column(name = "travel_frequency", length = 20)
    private GpProTravelFrequency travelFrequency;

    @Column(name = "commercial_name", length = 80)
    private String commercialName;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public GpProApplicationStatus getStatus() { return status; }
    public void setStatus(GpProApplicationStatus status) { this.status = status; }

    public GpProApplicationSource getSource() { return source; }
    public void setSource(GpProApplicationSource source) { this.source = source; }

    public GpProTravelFrequency getTravelFrequency() { return travelFrequency; }
    public void setTravelFrequency(GpProTravelFrequency travelFrequency) { this.travelFrequency = travelFrequency; }

    public String getCommercialName() { return commercialName; }
    public void setCommercialName(String commercialName) { this.commercialName = commercialName; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
}

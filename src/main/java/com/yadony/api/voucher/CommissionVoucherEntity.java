package com.yadony.api.voucher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bon de réduction de commission (lot 3, remplace le crédit portefeuille du
 * parrainage). Enregistrement immuable : pas de soft delete, {@code grantedAt}
 * sert de timestamp de création, {@code consumedAt} matérialise sa consommation
 * unique. La contrainte UNIQUE(source_invitation_id) garantit qu'une invitation
 * ne peut jamais octroyer deux bons.
 */
@Entity
@Table(name = "commission_vouchers",
       uniqueConstraints = @UniqueConstraint(columnNames = {"source_invitation_id"}))
public class CommissionVoucherEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Facteur multiplicatif appliqué au taux/prélèvement, ex. 0.50 = moitié prix. */
    @Column(name = "factor", nullable = false, precision = 3, scale = 2)
    private BigDecimal factor;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_on_bid_id")
    private UUID consumedOnBidId;

    @Column(name = "source_invitation_id", nullable = false)
    private UUID sourceInvitationId;

    // ── getters/setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public BigDecimal getFactor() { return factor; }
    public void setFactor(BigDecimal factor) { this.factor = factor; }

    public LocalDateTime getGrantedAt() { return grantedAt; }
    public void setGrantedAt(LocalDateTime grantedAt) { this.grantedAt = grantedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }

    public UUID getConsumedOnBidId() { return consumedOnBidId; }
    public void setConsumedOnBidId(UUID consumedOnBidId) { this.consumedOnBidId = consumedOnBidId; }

    public UUID getSourceInvitationId() { return sourceInvitationId; }
    public void setSourceInvitationId(UUID sourceInvitationId) { this.sourceInvitationId = sourceInvitationId; }
}

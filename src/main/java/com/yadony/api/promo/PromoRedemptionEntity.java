package com.yadony.api.promo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Enregistrement d'une utilisation de code promo.
 * N'étend pas {@code BaseEntity} — pas de soft delete, {@code redeemedAt} sert
 * de timestamp de création. La contrainte UNIQUE(promo_code_id, bid_id) garantit
 * l'idempotence.
 *
 * <p>Une utilisation peut être LIBÉRÉE ({@code releasedAt} non nul) quand l'envoi se termine
 * sans livraison et que la commission remisée n'est pas conservée : elle ne compte plus dans
 * les limites du code. La ligne n'est jamais supprimée ; un nouveau rachat du même code sur le
 * même bid la réactive.
 */
@Entity
@Table(name = "promo_redemptions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"promo_code_id", "bid_id"}))
public class PromoRedemptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "promo_code_id", nullable = false)
    private UUID promoCodeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "applied_rate", nullable = false, precision = 4, scale = 3)
    private BigDecimal appliedRate;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    /** Libération (code rendu) ; null = utilisation active, comptée dans les limites. */
    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    /** Motif de la libération (ex. {@code BID_CANCELLED}, {@code TRIP_CANCELLED}). */
    @Column(name = "release_reason", length = 40)
    private String releaseReason;

    // ── getters/setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getPromoCodeId() { return promoCodeId; }
    public void setPromoCodeId(UUID promoCodeId) { this.promoCodeId = promoCodeId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public BigDecimal getAppliedRate() { return appliedRate; }
    public void setAppliedRate(BigDecimal appliedRate) { this.appliedRate = appliedRate; }

    public LocalDateTime getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(LocalDateTime redeemedAt) { this.redeemedAt = redeemedAt; }

    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }

    public String getReleaseReason() { return releaseReason; }
    public void setReleaseReason(String releaseReason) { this.releaseReason = releaseReason; }

    public boolean isReleased() { return releasedAt != null; }
}

package com.yadony.api.payments.wallet;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Recharge du portefeuille remboursée hors de l'app (tableau de bord Stripe, support), cf.
 * {@link WalletExternalRefundService}. Tant qu'il est {@link WalletExternalRefundHoldStatus#OPEN}, la
 * devise est gelée ({@link WalletService#isFrozen}) : le crédit déjà rendu sur la carte ne peut plus
 * être dépensé. Seul un débit admin reste possible, pour régulariser.
 */
@Entity
@Table(name = "wallet_external_refund_holds")
@Where(clause = "deleted_at IS NULL")
public class WalletExternalRefundHoldEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Column(name = "stripe_refund_id", nullable = false, unique = true)
    private String stripeRefundId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private WalletExternalRefundHoldStatus status = WalletExternalRefundHoldStatus.OPEN;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_note", length = 500)
    private String releaseNote;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String paymentIntentId) { this.paymentIntentId = paymentIntentId; }
    public String getStripeRefundId() { return stripeRefundId; }
    public void setStripeRefundId(String stripeRefundId) { this.stripeRefundId = stripeRefundId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public WalletExternalRefundHoldStatus getStatus() { return status; }
    public void setStatus(WalletExternalRefundHoldStatus status) { this.status = status; }
    public UUID getReleasedBy() { return releasedBy; }
    public void setReleasedBy(UUID releasedBy) { this.releasedBy = releasedBy; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
    public String getReleaseNote() { return releaseNote; }
    public void setReleaseNote(String releaseNote) { this.releaseNote = releaseNote; }
}

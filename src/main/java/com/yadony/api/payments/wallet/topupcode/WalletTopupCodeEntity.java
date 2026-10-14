package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "wallet_topup_codes")
@Where(clause = "deleted_at IS NULL")
public class WalletTopupCodeEntity extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "max_redemptions", nullable = false)
    private int maxRedemptions;

    @Column(name = "redeemed_count", nullable = false)
    private int redeemedCount = 0;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WalletTopupCodeStatus status = WalletTopupCodeStatus.ACTIVE;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public WalletTopupCodeDisplayStatus displayStatus(LocalDateTime now) {
        if (status == WalletTopupCodeStatus.REVOKED) return WalletTopupCodeDisplayStatus.REVOKED;
        if (!expiresAt.isAfter(now)) return WalletTopupCodeDisplayStatus.EXPIRED;
        if (redeemedCount >= maxRedemptions) return WalletTopupCodeDisplayStatus.EXHAUSTED;
        return WalletTopupCodeDisplayStatus.ACTIVE;
    }

    public int remaining() { return Math.max(0, maxRedemptions - redeemedCount); }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code != null ? code.toUpperCase(Locale.ROOT) : null; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getMaxRedemptions() { return maxRedemptions; }
    public void setMaxRedemptions(int maxRedemptions) { this.maxRedemptions = maxRedemptions; }
    public int getRedeemedCount() { return redeemedCount; }
    public void setRedeemedCount(int redeemedCount) { this.redeemedCount = redeemedCount; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public WalletTopupCodeStatus getStatus() { return status; }
    public void setStatus(WalletTopupCodeStatus status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getRevokedBy() { return revokedBy; }
    public void setRevokedBy(UUID revokedBy) { this.revokedBy = revokedBy; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}

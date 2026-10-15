package com.yadony.api.payments.wallet.topupcode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Une utilisation d'un code par un compte. Ajout seul : l'historique ne disparaît jamais. */
@Entity
@Table(name = "wallet_topup_code_redemptions",
        uniqueConstraints = @UniqueConstraint(name = "uq_topup_code_redemption_user", columnNames = {"code_id", "user_id"}))
public class WalletTopupCodeRedemptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code_id", nullable = false, updatable = false)
    private UUID codeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "wallet_transaction_id", nullable = false, updatable = false)
    private UUID walletTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(ZoneOffset.UTC); }

    public UUID getId() { return id; }
    public UUID getCodeId() { return codeId; }
    public void setCodeId(UUID codeId) { this.codeId = codeId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public UUID getWalletTransactionId() { return walletTransactionId; }
    public void setWalletTransactionId(UUID walletTransactionId) { this.walletTransactionId = walletTransactionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

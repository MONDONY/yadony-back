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
 * Ajustement manuel du solde au-delà du seuil de double validation
 * ({@link WalletAdminAdjustmentService#APPROVAL_THRESHOLD_EUR}) : il attend l'approbation d'un
 * second admin. {@code idempotencyKey} est la clé d'origine de l'auteur, préfixée ; c'est celle
 * que portera le mouvement écrit à l'approbation.
 */
@Entity
@Table(name = "wallet_adjustment_requests")
@Where(clause = "deleted_at IS NULL")
public class WalletAdjustmentRequestEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "direction", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private WalletAdjustmentDirection direction;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "eur_equivalent", nullable = false, precision = 12, scale = 2)
    private BigDecimal eurEquivalent;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 220, unique = true)
    private String idempotencyKey;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private WalletAdjustmentRequestStatus status = WalletAdjustmentRequestStatus.PENDING;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "transaction_id")
    private UUID transactionId;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public WalletAdjustmentDirection getDirection() { return direction; }
    public void setDirection(WalletAdjustmentDirection direction) { this.direction = direction; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getEurEquivalent() { return eurEquivalent; }
    public void setEurEquivalent(BigDecimal eurEquivalent) { this.eurEquivalent = eurEquivalent; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }
    public WalletAdjustmentRequestStatus getStatus() { return status; }
    public void setStatus(WalletAdjustmentRequestStatus status) { this.status = status; }
    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
}

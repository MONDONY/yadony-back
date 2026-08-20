package com.yadony.api.payments.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_refund_request_items")
public class WalletRefundRequestItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "refund_request_id", nullable = false)
    private UUID refundRequestId;

    @Column(name = "wallet_transaction_id", nullable = false)
    private UUID walletTransactionId;

    @Column(name = "payment_intent_id", nullable = false, length = 255)
    private String paymentIntentId;

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private WalletRefundItemStatus status = WalletRefundItemStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getRefundRequestId() { return refundRequestId; }
    public void setRefundRequestId(UUID refundRequestId) { this.refundRequestId = refundRequestId; }
    public UUID getWalletTransactionId() { return walletTransactionId; }
    public void setWalletTransactionId(UUID walletTransactionId) { this.walletTransactionId = walletTransactionId; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String paymentIntentId) { this.paymentIntentId = paymentIntentId; }
    public String getStripeRefundId() { return stripeRefundId; }
    public void setStripeRefundId(String stripeRefundId) { this.stripeRefundId = stripeRefundId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public WalletRefundItemStatus getStatus() { return status; }
    public void setStatus(WalletRefundItemStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}

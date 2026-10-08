package com.yadony.api.payments.split;

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

/** Exécution d'un partage chiffré décidé par l'admin à la résolution d'un litige (V300, FLUTTER-E2). */
@Entity
@Table(name = "payment_splits")
@Where(clause = "deleted_at IS NULL")
public class PaymentSplitEntity extends BaseEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "dispute_id")
    private UUID disputeId;

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "traveler_id")
    private UUID travelerId;

    @Column(name = "sender_refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal senderRefundAmount;

    @Column(name = "traveler_payout_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal travelerPayoutAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private PaymentSplitMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentSplitStatus status = PaymentSplitStatus.CLAIMED;

    @Column(name = "stripe_refund_id")
    private String stripeRefundId;

    @Column(name = "stripe_capture_done", nullable = false)
    private boolean stripeCaptureDone = false;

    @Column(name = "stripe_transfer_id")
    private String stripeTransferId;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }
    public UUID getDisputeId() { return disputeId; }
    public void setDisputeId(UUID disputeId) { this.disputeId = disputeId; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public BigDecimal getSenderRefundAmount() { return senderRefundAmount; }
    public void setSenderRefundAmount(BigDecimal v) { this.senderRefundAmount = v; }
    public BigDecimal getTravelerPayoutAmount() { return travelerPayoutAmount; }
    public void setTravelerPayoutAmount(BigDecimal v) { this.travelerPayoutAmount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentSplitMode getMode() { return mode; }
    public void setMode(PaymentSplitMode mode) { this.mode = mode; }
    public PaymentSplitStatus getStatus() { return status; }
    public void setStatus(PaymentSplitStatus status) { this.status = status; }
    public String getStripeRefundId() { return stripeRefundId; }
    public void setStripeRefundId(String stripeRefundId) { this.stripeRefundId = stripeRefundId; }
    public boolean isStripeCaptureDone() { return stripeCaptureDone; }
    public void setStripeCaptureDone(boolean stripeCaptureDone) { this.stripeCaptureDone = stripeCaptureDone; }
    public String getStripeTransferId() { return stripeTransferId; }
    public void setStripeTransferId(String stripeTransferId) { this.stripeTransferId = stripeTransferId; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}

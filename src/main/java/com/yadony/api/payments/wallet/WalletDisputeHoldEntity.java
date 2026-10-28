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
 * Litige carte (chargeback) ouvert sur une recharge du portefeuille, cf. {@link WalletDisputeService}.
 * Tant qu'il est {@link WalletDisputeHoldStatus#OPEN}, la devise est gelée
 * ({@link WalletService#isFrozen}). {@code debitedAmount} est ce qui a pu être repris sur le solde
 * à l'ouverture, au plus {@code disputedAmount} : c'est ce qu'un litige gagné recrédite.
 */
@Entity
@Table(name = "wallet_dispute_holds")
@Where(clause = "deleted_at IS NULL")
public class WalletDisputeHoldEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "stripe_dispute_id", nullable = false, unique = true)
    private String stripeDisputeId;

    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Column(name = "disputed_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal disputedAmount;

    @Column(name = "debited_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal debitedAmount;

    @Column(name = "status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private WalletDisputeHoldStatus status = WalletDisputeHoldStatus.OPEN;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStripeDisputeId() { return stripeDisputeId; }
    public void setStripeDisputeId(String stripeDisputeId) { this.stripeDisputeId = stripeDisputeId; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String paymentIntentId) { this.paymentIntentId = paymentIntentId; }
    public BigDecimal getDisputedAmount() { return disputedAmount; }
    public void setDisputedAmount(BigDecimal disputedAmount) { this.disputedAmount = disputedAmount; }
    public BigDecimal getDebitedAmount() { return debitedAmount; }
    public void setDebitedAmount(BigDecimal debitedAmount) { this.debitedAmount = debitedAmount; }
    public WalletDisputeHoldStatus getStatus() { return status; }
    public void setStatus(WalletDisputeHoldStatus status) { this.status = status; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
}

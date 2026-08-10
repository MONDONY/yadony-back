package com.yadony.api.payments;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Where(clause = "deleted_at IS NULL")
public class PaymentEntity extends BaseEntity {

    @Column(name = "bid_id", unique = true)
    private UUID bidId;

    /**
     * Reference to a negotiation thread (package-request marketplace flow).
     * Mutually exclusive with bid_id — enforced by DB CHECK constraint.
     */
    @Column(name = "negotiation_thread_id", unique = true)
    private UUID negotiationThreadId;

    @Column(name = "stripe_payment_intent_id", nullable = false, unique = true, length = 255)
    private String stripePaymentIntentId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "eur";

    /** Stripe FX Quote used to lock the presentment-to-EUR conversion for this payment. */
    @Column(name = "stripe_fx_quote_id", length = 255)
    private String stripeFxQuoteId;

    @Column(name = "fx_exchange_rate", precision = 20, scale = 10)
    private BigDecimal fxExchangeRate;

    @Column(name = "fx_quote_expires_at")
    private Instant fxQuoteExpiresAt;

    @Column(name = "commission_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount;

    /** Montant cumulé remboursé dans la devise du paiement — miroir absolu de Stripe charge.amount_refunded. */
    @Column(name = "refunded_amount", precision = 10, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "escrow_released_at")
    private LocalDateTime escrowReleasedAt;

    @Column(name = "legacy_destination_charge", nullable = false)
    private boolean legacyDestinationCharge = false;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "disputed", nullable = false)
    private boolean disputed = false;

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getNegotiationThreadId() { return negotiationThreadId; }
    public void setNegotiationThreadId(UUID negotiationThreadId) { this.negotiationThreadId = negotiationThreadId; }

    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public void setStripePaymentIntentId(String stripePaymentIntentId) { this.stripePaymentIntentId = stripePaymentIntentId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency == null ? null : currency.toLowerCase(); }

    public String getStripeFxQuoteId() { return stripeFxQuoteId; }
    public void setStripeFxQuoteId(String stripeFxQuoteId) { this.stripeFxQuoteId = stripeFxQuoteId; }

    public BigDecimal getFxExchangeRate() { return fxExchangeRate; }
    public void setFxExchangeRate(BigDecimal fxExchangeRate) { this.fxExchangeRate = fxExchangeRate; }

    public Instant getFxQuoteExpiresAt() { return fxQuoteExpiresAt; }
    public void setFxQuoteExpiresAt(Instant fxQuoteExpiresAt) { this.fxQuoteExpiresAt = fxQuoteExpiresAt; }

    /** Clears obsolete Stripe FX data when this row is recycled for a 1:1 currency payment. */
    public void clearLegacyFxData() {
        stripeFxQuoteId = null;
        fxExchangeRate = null;
        fxQuoteExpiresAt = null;
    }

    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }

    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public LocalDateTime getEscrowReleasedAt() { return escrowReleasedAt; }
    public void setEscrowReleasedAt(LocalDateTime escrowReleasedAt) { this.escrowReleasedAt = escrowReleasedAt; }

    public boolean isLegacyDestinationCharge() { return legacyDestinationCharge; }
    public void setLegacyDestinationCharge(boolean legacyDestinationCharge) { this.legacyDestinationCharge = legacyDestinationCharge; }

    public String getStripeChargeId() { return stripeChargeId; }
    public void setStripeChargeId(String stripeChargeId) { this.stripeChargeId = stripeChargeId; }

    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }

    public boolean isDisputed() { return disputed; }
    public void setDisputed(boolean disputed) { this.disputed = disputed; }
}

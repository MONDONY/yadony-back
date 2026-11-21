package com.yadony.api.cancellation.fee;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Frais retenus sur le remboursement d'un expéditeur qui annule un colis accepté.
 * Une ligne par colis. En base (Postgres) l'unicité est un index partiel sur les lignes
 * non supprimées (V390) ; la contrainte déclarée ici sert au schéma H2 des tests.
 */
@Entity
@Table(name = "cancellation_fees",
        uniqueConstraints = @UniqueConstraint(name = "uq_cancellation_fees_bid_id", columnNames = "bid_id"))
@Where(clause = "deleted_at IS NULL")
public class CancellationFeeEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private CancellationFeeReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CancellationFeeStatus status;

    @Column(name = "gross_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossFee;

    @Column(name = "refund_fees", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundFees;

    @Column(name = "commission", nullable = false, precision = 12, scale = 2)
    private BigDecimal commission;

    @Column(name = "traveler_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal travelerNet;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "payout_ref", length = 100)
    private String payoutRef;

    /** Dernier motif de l'attente de versement (V392), {@value #PAYOUT_PENDING_REASON_MAX} caractères au plus. */
    @Column(name = "payout_pending_reason", length = PAYOUT_PENDING_REASON_MAX)
    private String payoutPendingReason;

    /** Relance du versement par un administrateur (V392) : le plafond d'essais repart de cette date. */
    @Column(name = "payout_rearmed_at")
    private LocalDateTime payoutRearmedAt;

    public static final int PAYOUT_PENDING_REASON_MAX = 255;

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public CancellationFeeReason getReason() { return reason; }
    public void setReason(CancellationFeeReason reason) { this.reason = reason; }

    public CancellationFeeStatus getStatus() { return status; }
    public void setStatus(CancellationFeeStatus status) { this.status = status; }

    public BigDecimal getGrossFee() { return grossFee; }
    public void setGrossFee(BigDecimal grossFee) { this.grossFee = grossFee; }

    public BigDecimal getRefundFees() { return refundFees; }
    public void setRefundFees(BigDecimal refundFees) { this.refundFees = refundFees; }

    public BigDecimal getCommission() { return commission; }
    public void setCommission(BigDecimal commission) { this.commission = commission; }

    public BigDecimal getTravelerNet() { return travelerNet; }
    public void setTravelerNet(BigDecimal travelerNet) { this.travelerNet = travelerNet; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getPayoutRef() { return payoutRef; }
    public void setPayoutRef(String payoutRef) { this.payoutRef = payoutRef; }

    public String getPayoutPendingReason() { return payoutPendingReason; }
    public void setPayoutPendingReason(String payoutPendingReason) { this.payoutPendingReason = payoutPendingReason; }

    public LocalDateTime getPayoutRearmedAt() { return payoutRearmedAt; }
    public void setPayoutRearmedAt(LocalDateTime payoutRearmedAt) { this.payoutRearmedAt = payoutRearmedAt; }
}

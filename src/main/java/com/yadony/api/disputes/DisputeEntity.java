package com.yadony.api.disputes;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "disputes")
@Where(clause = "deleted_at IS NULL")
public class DisputeEntity extends BaseEntity {

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "sender_id")
    private UUID senderId;

    @Column(name = "traveler_id")
    private UUID travelerId;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "refund_frozen", nullable = false)
    private boolean refundFrozen = false;

    @Column(name = "resolution_type", length = 40)
    private String resolutionType;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "beneficiary_user_id")
    private UUID beneficiaryUserId;

    @Column(name = "guarantee_amount_cents")
    private Long guaranteeAmountCents;

    /** Devise de {@code guaranteeAmountCents}, celle du bid du litige (code ISO en majuscules). */
    @Column(name = "guarantee_currency", length = 3)
    private String guaranteeCurrency;

    /** Partage décidé par l'admin (V300, FLUTTER-E2) : part remboursée à l'expéditeur. */
    @Column(name = "sender_refund_amount", precision = 10, scale = 2)
    private java.math.BigDecimal senderRefundAmount;

    /** Partage décidé par l'admin (V300) : part versée au voyageur. */
    @Column(name = "traveler_payout_amount", precision = 10, scale = 2)
    private java.math.BigDecimal travelerPayoutAmount;

    @Column(name = "split_currency", length = 3)
    private String splitCurrency;

    public java.math.BigDecimal getSenderRefundAmount() { return senderRefundAmount; }
    public void setSenderRefundAmount(java.math.BigDecimal v) { this.senderRefundAmount = v; }
    public java.math.BigDecimal getTravelerPayoutAmount() { return travelerPayoutAmount; }
    public void setTravelerPayoutAmount(java.math.BigDecimal v) { this.travelerPayoutAmount = v; }
    public String getSplitCurrency() { return splitCurrency; }
    public void setSplitCurrency(String splitCurrency) { this.splitCurrency = splitCurrency; }

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isRefundFrozen() { return refundFrozen; }
    public void setRefundFrozen(boolean refundFrozen) { this.refundFrozen = refundFrozen; }

    public String getResolutionType() { return resolutionType; }
    public void setResolutionType(String resolutionType) { this.resolutionType = resolutionType; }

    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public UUID getBeneficiaryUserId() { return beneficiaryUserId; }
    public void setBeneficiaryUserId(UUID beneficiaryUserId) { this.beneficiaryUserId = beneficiaryUserId; }

    public Long getGuaranteeAmountCents() { return guaranteeAmountCents; }
    public void setGuaranteeAmountCents(Long guaranteeAmountCents) { this.guaranteeAmountCents = guaranteeAmountCents; }

    public String getGuaranteeCurrency() { return guaranteeCurrency; }
    public void setGuaranteeCurrency(String guaranteeCurrency) { this.guaranteeCurrency = guaranteeCurrency; }
}

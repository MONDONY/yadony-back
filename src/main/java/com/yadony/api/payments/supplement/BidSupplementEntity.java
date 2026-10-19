package com.yadony.api.payments.supplement;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Supplément de prix demandé par le voyageur à la remise (V338). Jamais de ligne {@code payments}
 * ni de réservation {@code stripe_release_intents} : le supplément porte ses propres références
 * prestataires. Montants dans la devise du colis ; dates en UTC.
 */
@Entity
@Table(name = "bid_supplements")
@Where(clause = "deleted_at IS NULL")
public class BidSupplementEntity extends BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "bid_id", nullable = false, updatable = false)
    private UUID bidId;

    @Column(name = "traveler_id", nullable = false, updatable = false)
    private UUID travelerId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 10, updatable = false)
    private BidSupplementRail rail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BidSupplementStatus status = BidSupplementStatus.PROPOSED;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "net_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "commission_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "commission_grid_id")
    private UUID commissionGridId;

    @Column(name = "commission_rate_snapshot", precision = 7, scale = 6)
    private BigDecimal commissionRateSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 20)
    private BidSupplementReason reasonCode;

    @Column(name = "reason_text", length = 500)
    private String reasonText;

    @Column(name = "grid_item_label", length = 120)
    private String gridItemLabel;

    /** Clé R2 {@code tracking/<bidId>/…}, revendiquée par {@code BidSupplementPhotoReferenceSource}. */
    @Column(name = "photo_key", nullable = false, length = 512)
    private String photoKey;

    @Column(name = "photo_purged_at")
    private LocalDateTime photoPurgedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "paid_out_at")
    private LocalDateTime paidOutAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "closed_reason", length = 40)
    private String closedReason;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "stripe_pi_attempt", nullable = false)
    private int stripePiAttempt;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "stripe_transfer_id", length = 255)
    private String stripeTransferId;

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_charged_via", length = 10)
    private CommissionChargedVia commissionChargedVia;

    @Column(name = "commission_payment_intent_id", length = 255)
    private String commissionPaymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_status", length = 20)
    private CommissionStatus commissionStatus;

    public Long getVersion() { return version; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }
    public BidSupplementRail getRail() { return rail; }
    public void setRail(BidSupplementRail rail) { this.rail = rail; }
    public BidSupplementStatus getStatus() { return status; }
    public void setStatus(BidSupplementStatus status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public UUID getCommissionGridId() { return commissionGridId; }
    public void setCommissionGridId(UUID commissionGridId) { this.commissionGridId = commissionGridId; }
    public BigDecimal getCommissionRateSnapshot() { return commissionRateSnapshot; }
    public void setCommissionRateSnapshot(BigDecimal commissionRateSnapshot) { this.commissionRateSnapshot = commissionRateSnapshot; }
    public BidSupplementReason getReasonCode() { return reasonCode; }
    public void setReasonCode(BidSupplementReason reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public String getGridItemLabel() { return gridItemLabel; }
    public void setGridItemLabel(String gridItemLabel) { this.gridItemLabel = gridItemLabel; }
    public String getPhotoKey() { return photoKey; }
    public void setPhotoKey(String photoKey) { this.photoKey = photoKey; }
    public LocalDateTime getPhotoPurgedAt() { return photoPurgedAt; }
    /** Photo supprimée de R2 par la purge après livraison : plus d'URL, plus revendiquée. */
    public void markPhotoPurged(LocalDateTime at) { this.photoPurgedAt = at; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(LocalDateTime respondedAt) { this.respondedAt = respondedAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getPaidOutAt() { return paidOutAt; }
    public void setPaidOutAt(LocalDateTime paidOutAt) { this.paidOutAt = paidOutAt; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }
    public String getClosedReason() { return closedReason; }
    public void setClosedReason(String closedReason) { this.closedReason = closedReason; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public void setStripePaymentIntentId(String stripePaymentIntentId) { this.stripePaymentIntentId = stripePaymentIntentId; }
    public int getStripePiAttempt() { return stripePiAttempt; }
    public void setStripePiAttempt(int stripePiAttempt) { this.stripePiAttempt = stripePiAttempt; }
    public String getStripeChargeId() { return stripeChargeId; }
    public void setStripeChargeId(String stripeChargeId) { this.stripeChargeId = stripeChargeId; }
    public String getStripeTransferId() { return stripeTransferId; }
    public void setStripeTransferId(String stripeTransferId) { this.stripeTransferId = stripeTransferId; }
    public String getStripeRefundId() { return stripeRefundId; }
    public void setStripeRefundId(String stripeRefundId) { this.stripeRefundId = stripeRefundId; }
    public CommissionChargedVia getCommissionChargedVia() { return commissionChargedVia; }
    public void setCommissionChargedVia(CommissionChargedVia commissionChargedVia) { this.commissionChargedVia = commissionChargedVia; }
    public String getCommissionPaymentIntentId() { return commissionPaymentIntentId; }
    public void setCommissionPaymentIntentId(String commissionPaymentIntentId) { this.commissionPaymentIntentId = commissionPaymentIntentId; }
    public CommissionStatus getCommissionStatus() { return commissionStatus; }
    public void setCommissionStatus(CommissionStatus commissionStatus) { this.commissionStatus = commissionStatus; }
}

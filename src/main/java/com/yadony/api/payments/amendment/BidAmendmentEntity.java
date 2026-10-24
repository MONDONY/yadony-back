package com.yadony.api.payments.amendment;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.BidSupplementRail;
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
 * Modification d'un colis accepté : l'expéditeur ajoute des kilos ou des articles de grille, payés comme
 * une 2e transaction (V347). Jamais de ligne {@code payments} ni de réservation
 * {@code stripe_release_intents} : la modification porte ses propres références prestataires, et
 * {@code bids.weight_kg}, {@code bid_grid_items} et le paiement du principal ne changent jamais.
 * Montants dans la devise du colis ; dates en UTC. Le rail est celui choisi par l'expéditeur
 * ({@link BidSupplementRail}, réutilisé), pas forcément celui du colis.
 */
@Entity
@Table(name = "bid_amendments")
@Where(clause = "deleted_at IS NULL")
public class BidAmendmentEntity extends BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "bid_id", nullable = false, updatable = false)
    private UUID bidId;

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Column(name = "traveler_id", nullable = false, updatable = false)
    private UUID travelerId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 10, updatable = false)
    private BidSupplementRail rail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private BidAmendmentStatus status = BidAmendmentStatus.AUTHORIZATION_PENDING;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    /** Kilos ajoutés par la demande. */
    @Column(name = "added_weight_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal addedWeightKg = BigDecimal.ZERO;

    /** Kilos réellement retirés de l'annonce : remis à 0 quand ils sont rendus (une seule fois). */
    @Column(name = "capacity_reserved_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal capacityReservedKg = BigDecimal.ZERO;

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

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "payment_expires_at")
    private LocalDateTime paymentExpiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "paid_out_at")
    private LocalDateTime paidOutAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    /**
     * Passage en {@code PROPOSED} : à la création (espèces, mobile money) ou à l'autorisation du montant (carte).
     * Nul : la demande n'a jamais été présentée au voyageur, qui ne la voit pas.
     */
    @Column(name = "proposed_at")
    private LocalDateTime proposedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closed_reason", length = 40)
    private BidAmendmentClosedReason closedReason;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "stripe_transfer_id", length = 255)
    private String stripeTransferId;

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    /** Autorisation carte d'une modification close rendue (PaymentIntent annulée) : le filet ne la relit plus. */
    @Column(name = "authorization_released_at")
    private LocalDateTime authorizationReleasedAt;

    /** Essai de capture : incrémenté après une réponse 5xx de Stripe (nouvelle clé d'idempotence). */
    @Column(name = "release_attempted_at")
    private LocalDateTime releaseAttemptedAt;

    @Column(name = "capture_attempt", nullable = false)
    private int captureAttempt;

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
    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }
    public BidSupplementRail getRail() { return rail; }
    public void setRail(BidSupplementRail rail) { this.rail = rail; }
    public BidAmendmentStatus getStatus() { return status; }
    public void setStatus(BidAmendmentStatus status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getAddedWeightKg() { return addedWeightKg; }
    public void setAddedWeightKg(BigDecimal addedWeightKg) { this.addedWeightKg = addedWeightKg; }
    public BigDecimal getCapacityReservedKg() { return capacityReservedKg; }
    public void setCapacityReservedKg(BigDecimal capacityReservedKg) { this.capacityReservedKg = capacityReservedKg; }
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
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getPaymentExpiresAt() { return paymentExpiresAt; }
    public void setPaymentExpiresAt(LocalDateTime paymentExpiresAt) { this.paymentExpiresAt = paymentExpiresAt; }
    public LocalDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(LocalDateTime respondedAt) { this.respondedAt = respondedAt; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public LocalDateTime getPaidOutAt() { return paidOutAt; }
    public void setPaidOutAt(LocalDateTime paidOutAt) { this.paidOutAt = paidOutAt; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }
    public LocalDateTime getReminderSentAt() { return reminderSentAt; }
    public void setReminderSentAt(LocalDateTime reminderSentAt) { this.reminderSentAt = reminderSentAt; }
    public LocalDateTime getProposedAt() { return proposedAt; }
    public void setProposedAt(LocalDateTime proposedAt) { this.proposedAt = proposedAt; }
    public BidAmendmentClosedReason getClosedReason() { return closedReason; }
    public void setClosedReason(BidAmendmentClosedReason closedReason) { this.closedReason = closedReason; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public void setStripePaymentIntentId(String stripePaymentIntentId) { this.stripePaymentIntentId = stripePaymentIntentId; }
    public String getStripeChargeId() { return stripeChargeId; }
    public void setStripeChargeId(String stripeChargeId) { this.stripeChargeId = stripeChargeId; }
    public String getStripeTransferId() { return stripeTransferId; }
    public void setStripeTransferId(String stripeTransferId) { this.stripeTransferId = stripeTransferId; }
    public String getStripeRefundId() { return stripeRefundId; }
    public void setStripeRefundId(String stripeRefundId) { this.stripeRefundId = stripeRefundId; }
    public LocalDateTime getAuthorizationReleasedAt() { return authorizationReleasedAt; }
    public void setAuthorizationReleasedAt(LocalDateTime authorizationReleasedAt) { this.authorizationReleasedAt = authorizationReleasedAt; }
    public LocalDateTime getReleaseAttemptedAt() { return releaseAttemptedAt; }
    public void setReleaseAttemptedAt(LocalDateTime releaseAttemptedAt) { this.releaseAttemptedAt = releaseAttemptedAt; }
    public int getCaptureAttempt() { return captureAttempt; }
    public void setCaptureAttempt(int captureAttempt) { this.captureAttempt = captureAttempt; }
    public CommissionChargedVia getCommissionChargedVia() { return commissionChargedVia; }
    public void setCommissionChargedVia(CommissionChargedVia commissionChargedVia) { this.commissionChargedVia = commissionChargedVia; }
    public String getCommissionPaymentIntentId() { return commissionPaymentIntentId; }
    public void setCommissionPaymentIntentId(String commissionPaymentIntentId) { this.commissionPaymentIntentId = commissionPaymentIntentId; }
    public CommissionStatus getCommissionStatus() { return commissionStatus; }
    public void setCommissionStatus(CommissionStatus commissionStatus) { this.commissionStatus = commissionStatus; }
}

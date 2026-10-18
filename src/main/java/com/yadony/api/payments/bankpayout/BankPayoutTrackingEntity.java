package com.yadony.api.payments.bankpayout;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Suivi du virement bancaire d'un paiement carte versé au voyageur (V331) : Transfer de la
 * livraison, date de disponibilité sur le compte Connect, puis dernier payout Stripe connu qui le
 * contient. {@code payoutStatus} garde le statut brut Stripe ({@code pending}, {@code in_transit},
 * {@code paid}, {@code failed}, {@code canceled}) ; le statut montré à l'app est dérivé par
 * {@link BankPayoutStatus#derive}.
 */
@Entity
@Table(name = "bank_payout_tracking")
@Where(clause = "deleted_at IS NULL")
public class BankPayoutTrackingEntity extends BaseEntity {

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "traveler_id")
    private UUID travelerId;

    @Column(name = "transfer_id", nullable = false)
    private String transferId;

    @Column(name = "connected_account_id", nullable = false)
    private String connectedAccountId;

    @Column(name = "destination_payment_id")
    private String destinationPaymentId;

    @Column(name = "available_on")
    private LocalDate availableOn;

    @Column(name = "payout_id")
    private String payoutId;

    @Column(name = "payout_status", length = 20)
    private String payoutStatus;

    /** Date de création (côté Stripe) du dernier événement payout appliqué : garde d'ordre. */
    @Column(name = "payout_event_at")
    private Instant payoutEventAt;

    @Column(name = "arrival_date")
    private LocalDate arrivalDate;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    /** {@code <payoutId>:<statut>} déjà notifié : un rejeu ne renvoie ni push ni alerte. */
    @Column(name = "notified_key", length = 300)
    private String notifiedKey;

    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getConnectedAccountId() { return connectedAccountId; }
    public void setConnectedAccountId(String connectedAccountId) { this.connectedAccountId = connectedAccountId; }

    public String getDestinationPaymentId() { return destinationPaymentId; }
    public void setDestinationPaymentId(String destinationPaymentId) { this.destinationPaymentId = destinationPaymentId; }

    public LocalDate getAvailableOn() { return availableOn; }
    public void setAvailableOn(LocalDate availableOn) { this.availableOn = availableOn; }

    public String getPayoutId() { return payoutId; }
    public void setPayoutId(String payoutId) { this.payoutId = payoutId; }

    public String getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(String payoutStatus) { this.payoutStatus = payoutStatus; }

    public Instant getPayoutEventAt() { return payoutEventAt; }
    public void setPayoutEventAt(Instant payoutEventAt) { this.payoutEventAt = payoutEventAt; }

    public LocalDate getArrivalDate() { return arrivalDate; }
    public void setArrivalDate(LocalDate arrivalDate) { this.arrivalDate = arrivalDate; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getNotifiedKey() { return notifiedKey; }
    public void setNotifiedKey(String notifiedKey) { this.notifiedKey = notifiedKey; }
}

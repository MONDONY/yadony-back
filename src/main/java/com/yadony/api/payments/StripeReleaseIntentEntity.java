package com.yadony.api.payments;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Versement carte réservé : le claim ESCROW → RELEASED est commité, l'appel Stripe (Transfer,
 * ou capture d'une destination charge legacy) reste à faire ou à confirmer (PROD_CHECKLIST 9.23).
 *
 * <p>Écrite dans la même transaction que le claim, elle porte tout ce qu'il faut pour rejouer
 * l'appel à l'identique : montant, devise, destination, charge source, clé d'idempotence dérivée
 * du paiement. {@link StripeReleaseExecutor} fait l'appel hors transaction puis finalise par des
 * UPDATE conditionnels ; {@link StripeReleaseRecoveryJob} reprend les lignes dont le bail est échu.
 *
 * <p>Toutes les transitions passent par des UPDATE ciblés du repository, jamais par un
 * {@code save} de cette entité après sa création.
 */
@Entity
@Table(name = "stripe_release_intents")
@Where(clause = "deleted_at IS NULL")
public class StripeReleaseIntentEntity extends BaseEntity {

    public enum Kind { TRANSFER, LEGACY_CAPTURE }

    public enum Status { PENDING, DONE, REVERTED, CANCELLED }

    @Column(name = "payment_id", nullable = false, unique = true, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "amount_minor")
    private Long amountMinor;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "destination", length = 255)
    private String destination;

    @Column(name = "source_transaction", length = 255)
    private String sourceTransaction;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "audit_action", nullable = false, length = 64)
    private String auditAction;

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "traveler_id")
    private UUID travelerId;

    @Column(name = "sender_id")
    private UUID senderId;

    /** Garde levée par une dérogation admin, une par une (DISPUTED, BENEFICIARY_HELD). */
    public static final String GUARD_DISPUTED = "DISPUTED";
    public static final String GUARD_BENEFICIARY_HELD = "BENEFICIARY_HELD";

    /**
     * Gardes levées par une dérogation admin motivée et auditée (liste séparée par des virgules),
     * {@code null} si aucune. Seules {@link #GUARD_DISPUTED} et {@link #GUARD_BENEFICIARY_HELD}
     * peuvent l'être ; litige admin, colis annulé ou remboursé et statut du paiement, jamais.
     */
    @Column(name = "overridden_guards", length = 64)
    private String overriddenGuards;

    /** Révision : clé d'idempotence {@code transfer-<paymentId>-r<revision>}. */
    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "lease_until", nullable = false)
    private LocalDateTime leaseUntil;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "stripe_object_id", length = 255)
    private String stripeObjectId;

    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getSourceTransaction() { return sourceTransaction; }
    public void setSourceTransaction(String sourceTransaction) { this.sourceTransaction = sourceTransaction; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getAuditAction() { return auditAction; }
    public void setAuditAction(String auditAction) { this.auditAction = auditAction; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }
    public String getOverriddenGuards() { return overriddenGuards; }
    public void setOverriddenGuards(String overriddenGuards) { this.overriddenGuards = overriddenGuards; }

    /** Vrai si CETTE garde, et elle seule, a été levée par la dérogation. */
    public boolean overrides(String guard) {
        return overriddenGuards != null && java.util.Arrays.asList(overriddenGuards.split(",")).contains(guard);
    }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getStripeObjectId() { return stripeObjectId; }
    public void setStripeObjectId(String stripeObjectId) { this.stripeObjectId = stripeObjectId; }
}

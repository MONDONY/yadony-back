package com.yadony.api.cancellation;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cancellations",
        uniqueConstraints = @UniqueConstraint(name = "uq_cancellations_bid_id_scope", columnNames = {"bid_id", "scope"}))
public class CancellationEntity extends BaseEntity {

    // Unicité désormais portée par (bid_id, scope) — voir uniqueConstraints
    // ci-dessus et migration V173. Ne plus mettre `unique = true` ici seul,
    // sinon Hibernate (ddl-auto=create, tests H2) régénère une contrainte
    // mono-colonne qui interdirait un HANDOVER + un DELIVERY pour le même bid.
    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "cancelled_by", nullable = false)
    private UUID cancelledBy;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "refund_status", nullable = false, length = 20)
    private String refundStatus = "PENDING";

    @Column(name = "rematch_status", nullable = false, length = 20)
    private String rematchStatus = "NONE";

    @Enumerated(EnumType.STRING)
    @Column(name = "no_show_status", nullable = false, length = 25)
    private CancellationStatus noShowStatus = CancellationStatus.CONFIRMED;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private CancellationScope scope = CancellationScope.HANDOVER;

    @Column(name = "contestation_deadline")
    private OffsetDateTime contestationDeadline;

    // Décision d'un administrateur (V273) : nulle tant que personne n'a tranché.
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_decision", length = 10)
    private NoShowAdminDecision adminDecision;

    @Column(name = "decided_by_admin_id")
    private UUID decidedByAdminId;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    // Procédure « destinataire absent » (V301, FLUTTER-E2). Nulles hors signalement
    // RECIPIENT_NO_SHOW fait avec la procédure (les signalements antérieurs n'ont pas de garde).

    /** Preuve de tentative de contact retenue au signalement : {@code CALL} ou {@code MESSAGE}. */
    @Column(name = "contact_proof", length = 10)
    private String contactProof;

    /** Le voyageur a confirmé avoir attendu et tenté de joindre le destinataire. */
    @Column(name = "contact_confirmed_at")
    private OffsetDateTime contactConfirmedAt;

    /** Fin de la garde du colis par le voyageur ; au-delà, le colis passe « non réclamé ». */
    @Column(name = "hold_until")
    private OffsetDateTime holdUntil;

    /** Nouveau rendez-vous de livraison fixé par l'expéditeur pendant la garde. */
    @Column(name = "retry_appointment_at")
    private OffsetDateTime retryAppointmentAt;

    @Column(name = "retry_appointment_note", columnDefinition = "TEXT")
    private String retryAppointmentNote;

    /**
     * Colis passé « non réclamé ». Écrite uniquement par le claim atomique
     * {@link CancellationRepository#markUnclaimed} : jamais par le flush d'une entité chargée
     * avant lui, qui l'effacerait (pas de {@code @DynamicUpdate} ici).
     */
    @Column(name = "unclaimed_at", insertable = false, updatable = false)
    private OffsetDateTime unclaimedAt;

    public String getContactProof() { return contactProof; }
    public void setContactProof(String contactProof) { this.contactProof = contactProof; }

    public OffsetDateTime getContactConfirmedAt() { return contactConfirmedAt; }
    public void setContactConfirmedAt(OffsetDateTime contactConfirmedAt) { this.contactConfirmedAt = contactConfirmedAt; }

    public OffsetDateTime getHoldUntil() { return holdUntil; }
    public void setHoldUntil(OffsetDateTime holdUntil) { this.holdUntil = holdUntil; }

    public OffsetDateTime getRetryAppointmentAt() { return retryAppointmentAt; }
    public void setRetryAppointmentAt(OffsetDateTime retryAppointmentAt) { this.retryAppointmentAt = retryAppointmentAt; }

    public String getRetryAppointmentNote() { return retryAppointmentNote; }
    public void setRetryAppointmentNote(String retryAppointmentNote) { this.retryAppointmentNote = retryAppointmentNote; }

    public OffsetDateTime getUnclaimedAt() { return unclaimedAt; }
    /** Réservé aux tests unitaires (la colonne n'est écrite en base que par le claim). */
    void setUnclaimedAtForTest(OffsetDateTime unclaimedAt) { this.unclaimedAt = unclaimedAt; }

    public NoShowAdminDecision getAdminDecision() { return adminDecision; }
    public void setAdminDecision(NoShowAdminDecision adminDecision) { this.adminDecision = adminDecision; }

    public UUID getDecidedByAdminId() { return decidedByAdminId; }
    public void setDecidedByAdminId(UUID decidedByAdminId) { this.decidedByAdminId = decidedByAdminId; }

    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(UUID cancelledBy) { this.cancelledBy = cancelledBy; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }

    public String getRematchStatus() { return rematchStatus; }
    public void setRematchStatus(String rematchStatus) { this.rematchStatus = rematchStatus; }

    public CancellationStatus getNoShowStatus() { return noShowStatus; }
    public void setNoShowStatus(CancellationStatus noShowStatus) { this.noShowStatus = noShowStatus; }

    public CancellationScope getScope() { return scope; }
    public void setScope(CancellationScope scope) { this.scope = scope; }

    public OffsetDateTime getContestationDeadline() { return contestationDeadline; }
    public void setContestationDeadline(OffsetDateTime contestationDeadline) { this.contestationDeadline = contestationDeadline; }
}

package com.yadony.api.kyc;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "kyc_verifications", schema = "kyc_schema")
@Where(clause = "deleted_at IS NULL")
public class KycVerificationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "verification_session_id", length = 255)
    private String verificationSessionId;

    /**
     * Fournisseur ayant produit la session courante. Toute relecture (abandon, nom verifie,
     * vue admin) passe par lui et non par le fournisseur actif : une ligne verifiee du temps
     * de Stripe reste lisible apres la bascule vers Didit.
     */
    @Column(name = "provider", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private VerificationProviderKind provider = VerificationProviderKind.STRIPE;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private KycVerificationStatus status = KycVerificationStatus.PENDING;

    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;

    @Column(name = "rejection_code", length = 64)
    private String rejectionCode;

    /** Administrateur auteur de la derniere decision manuelle (V268). */
    @Column(name = "decided_by_admin_id")
    private UUID decidedByAdminId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Motif interne de l'administrateur : jamais renvoye a l'utilisateur. */
    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "decision_kind", length = 20)
    @Enumerated(EnumType.STRING)
    private KycDecisionKind decisionKind;

    /**
     * Passage en revue manuelle chez le fournisseur : le parcours de l'utilisateur est
     * termine et la ligne attend une decision. Seul critere fiable, {@code users.kyc_status}
     * passant deja a PENDING des la creation de la session.
     */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /**
     * Efface la decision et le passage en revue : nouvelle session ou reset administrateur,
     * la ligne repart d'un parcours vierge.
     */
    public void clearDecision() {
        decidedByAdminId = null;
        decidedAt = null;
        decisionReason = null;
        decisionKind = null;
        submittedAt = null;
    }

    /** Vrai quand une decision d'administrateur negative fige la ligne face au fournisseur. */
    public boolean isLockedByAdmin() {
        return decisionKind != null && decisionKind.overridesProvider();
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getVerificationSessionId() { return verificationSessionId; }
    public void setVerificationSessionId(String id) { this.verificationSessionId = id; }

    public VerificationProviderKind getProvider() { return provider; }
    public void setProvider(VerificationProviderKind provider) { this.provider = provider; }

    public KycVerificationStatus getStatus() { return status; }
    public void setStatus(KycVerificationStatus status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getRejectionCode() { return rejectionCode; }
    public void setRejectionCode(String rejectionCode) { this.rejectionCode = rejectionCode; }

    public UUID getDecidedByAdminId() { return decidedByAdminId; }
    public void setDecidedByAdminId(UUID decidedByAdminId) { this.decidedByAdminId = decidedByAdminId; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public KycDecisionKind getDecisionKind() { return decisionKind; }
    public void setDecisionKind(KycDecisionKind decisionKind) { this.decisionKind = decisionKind; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
}

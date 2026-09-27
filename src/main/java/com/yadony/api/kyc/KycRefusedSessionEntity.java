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

/**
 * Session de verification sur laquelle un administrateur a pris une decision negative (refus ou
 * revocation), V270. Survit a la nouvelle session qui efface la decision de la ligne
 * {@code kyc_verifications} : c'est ce qui empeche Didit, qui resert une session inachevee du
 * meme utilisateur, de faire verifier plus tard une session deja refusee.
 */
@Entity
@Table(name = "kyc_refused_sessions", schema = "kyc_schema")
@Where(clause = "deleted_at IS NULL")
public class KycRefusedSessionEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20, updatable = false)
    private VerificationProviderKind provider;

    @Column(name = "session_id", nullable = false, unique = true, length = 255, updatable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_kind", nullable = false, length = 20, updatable = false)
    private KycDecisionKind decisionKind;

    @Column(name = "decided_by_admin_id", updatable = false)
    private UUID decidedByAdminId;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private LocalDateTime decidedAt;

    protected KycRefusedSessionEntity() {
    }

    public KycRefusedSessionEntity(UUID userId, VerificationProviderKind provider, String sessionId,
                                   KycDecisionKind decisionKind, UUID decidedByAdminId, LocalDateTime decidedAt) {
        this.userId = userId;
        this.provider = provider;
        this.sessionId = sessionId;
        this.decisionKind = decisionKind;
        this.decidedByAdminId = decidedByAdminId;
        this.decidedAt = decidedAt;
    }

    public UUID getUserId() { return userId; }
    public VerificationProviderKind getProvider() { return provider; }
    public String getSessionId() { return sessionId; }
    public KycDecisionKind getDecisionKind() { return decisionKind; }
    public UUID getDecidedByAdminId() { return decidedByAdminId; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
}

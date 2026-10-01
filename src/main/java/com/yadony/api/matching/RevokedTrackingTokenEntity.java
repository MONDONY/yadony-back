package com.yadony.api.matching;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lien de suivi public d'un colis qui ne vaut plus : la page publique de l'ancien
 * jeton explique pourquoi (le destinataire a changé) au lieu d'un « lien invalide ».
 */
@Entity
@Table(name = "revoked_tracking_tokens")
@Where(clause = "deleted_at IS NULL")
public class RevokedTrackingTokenEntity extends BaseEntity {

    /** Seul motif aujourd'hui, aligné sur le CHECK de V282. */
    public enum Reason { RECIPIENT_CHANGED }

    @Column(name = "token", nullable = false, unique = true, length = 36)
    private String token;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private Reason reason;

    @Column(name = "revoked_at", nullable = false)
    private OffsetDateTime revokedAt;

    protected RevokedTrackingTokenEntity() {}

    public RevokedTrackingTokenEntity(String token, UUID bidId, Reason reason, OffsetDateTime revokedAt) {
        this.token = token;
        this.bidId = bidId;
        this.reason = reason;
        this.revokedAt = revokedAt;
    }

    public String getToken() { return token; }

    public UUID getBidId() { return bidId; }

    public Reason getReason() { return reason; }

    public OffsetDateTime getRevokedAt() { return revokedAt; }
}

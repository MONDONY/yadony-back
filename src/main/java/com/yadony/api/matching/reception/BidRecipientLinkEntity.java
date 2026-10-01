package com.yadony.api.matching.reception;

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
 * Rattachement d'un colis au compte Yadony de son destinataire, trouvé par le numéro
 * saisi par l'expéditeur ({@code bids.recipient_phone}). Un seul lien actif par colis
 * (index unique partiel V282) : changer de destinataire soft-delete l'ancien.
 */
@Entity
@Table(name = "bid_recipient_links")
@Where(clause = "deleted_at IS NULL")
public class BidRecipientLinkEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReceptionLinkStatus status = ReceptionLinkStatus.PENDING;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    protected BidRecipientLinkEntity() {}

    public BidRecipientLinkEntity(UUID bidId, UUID recipientUserId) {
        this.bidId = bidId;
        this.recipientUserId = recipientUserId;
        this.status = ReceptionLinkStatus.PENDING;
    }

    public UUID getBidId() { return bidId; }

    public UUID getRecipientUserId() { return recipientUserId; }

    public ReceptionLinkStatus getStatus() { return status; }

    public OffsetDateTime getRespondedAt() { return respondedAt; }

    /** Enregistre la réponse du destinataire. */
    public void respond(ReceptionLinkStatus answer, OffsetDateTime at) {
        this.status = answer;
        this.respondedAt = at;
    }
}

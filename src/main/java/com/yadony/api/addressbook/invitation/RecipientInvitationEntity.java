package com.yadony.api.addressbook.invitation;

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
 * Invitation d'un expéditeur à ajouter un utilisateur Yadony à son carnet de destinataires.
 *
 * <p>La cible (numéro ou email) n'est JAMAIS stockée en clair : seulement son empreinte
 * SHA-256 ({@code target_hash}, rattrapage et anti-doublon) et sa forme masquée affichée à
 * l'inviteur ({@code masked_target}).
 */
@Entity
@Table(name = "recipient_invitations")
@Where(clause = "deleted_at IS NULL")
public class RecipientInvitationEntity extends BaseEntity {

    @Column(name = "inviter_user_id", nullable = false)
    private UUID inviterUserId;

    @Column(name = "invitee_user_id")
    private UUID inviteeUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private InvitationChannel channel;

    @Column(name = "target_hash", nullable = false, length = 64)
    private String targetHash;

    @Column(name = "masked_target", nullable = false, length = 64)
    private String maskedTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "recipient_id")
    private UUID recipientId;

    protected RecipientInvitationEntity() {}

    public RecipientInvitationEntity(UUID inviterUserId, UUID inviteeUserId, InvitationChannel channel,
                                     String targetHash, String maskedTarget) {
        this.inviterUserId = inviterUserId;
        this.inviteeUserId = inviteeUserId;
        this.channel = channel;
        this.targetHash = targetHash;
        this.maskedTarget = maskedTarget;
        this.status = InvitationStatus.PENDING;
    }

    public UUID getInviterUserId() { return inviterUserId; }

    public UUID getInviteeUserId() { return inviteeUserId; }

    public void setInviteeUserId(UUID inviteeUserId) { this.inviteeUserId = inviteeUserId; }

    public InvitationChannel getChannel() { return channel; }

    public String getTargetHash() { return targetHash; }

    public String getMaskedTarget() { return maskedTarget; }

    public InvitationStatus getStatus() { return status; }

    public OffsetDateTime getRespondedAt() { return respondedAt; }

    public UUID getRecipientId() { return recipientId; }

    public void setRecipientId(UUID recipientId) { this.recipientId = recipientId; }

    /** Change le statut et horodate la réponse. */
    public void respond(InvitationStatus answer, OffsetDateTime at) {
        this.status = answer;
        this.respondedAt = at;
    }
}

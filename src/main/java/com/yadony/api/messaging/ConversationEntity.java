package com.yadony.api.messaging;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Conversation entre deux participants, adossée à un bid.
 *
 * <p><b>Participants selon le type</b> ({@link ConversationKind}) :
 * <ul>
 *   <li>{@code SENDER_TRAVELER} : {@code senderId} = expéditeur du bid, {@code travelerId} = voyageur ;</li>
 *   <li>{@code RECIPIENT_TRAVELER} : {@code senderId} = <b>destinataire</b> rattaché au colis
 *       (lien CONFIRMED), {@code travelerId} = voyageur.</li>
 * </ul>
 * Le nom de colonne est historique (et partagé avec le champ Firestore {@code senderId}, lu par
 * la Cloud Function et les règles). Tout code qui a besoin de « l'expéditeur du bid » doit le lire
 * sur le bid, jamais ici ; {@link #participantAId()} nomme ce que la colonne porte vraiment.
 *
 * <p>L'unicité est par {@code (bid_id, kind)} parmi les conversations non fermées (index V283).
 */
@Entity
@Table(name = "conversations")
public class ConversationEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    /** Participant A : expéditeur (SENDER_TRAVELER) ou destinataire (RECIPIENT_TRAVELER). */
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "firestore_conversation_id", nullable = false, unique = true)
    private String firestoreConversationId;

    @Column(name = "sender_deleted_at")
    private LocalDateTime senderDeletedAt;

    @Column(name = "traveler_deleted_at")
    private LocalDateTime travelerDeletedAt;

    @Column(name = "sender_archived_at")
    private LocalDateTime senderArchivedAt;

    @Column(name = "traveler_archived_at")
    private LocalDateTime travelerArchivedAt;

    /**
     * Sourdine (FLUTTER-CM, V294) : non nul quand ce participant a coupé les push des nouveaux
     * messages de ce fil. Propre à chaque participant, invisible pour l'autre. Ne touche ni au
     * compteur de non-lus ni aux appels. Distinct du mute de modération
     * ({@code UserEntity#isMessagingMuted}), qui empêche d'ÉCRIRE.
     */
    @Column(name = "sender_notifications_muted_at")
    private LocalDateTime senderNotificationsMutedAt;

    @Column(name = "traveler_notifications_muted_at")
    private LocalDateTime travelerNotificationsMutedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ConversationKind kind = ConversationKind.SENDER_TRAVELER;

    /**
     * Fermeture d'une conversation destinataire (changement de destinataire) : le participant A
     * n'y a plus accès, le voyageur la garde en lecture seule. Toujours nul pour SENDER_TRAVELER.
     */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public ConversationEntity() {}

    public ConversationEntity(UUID bidId, UUID senderId, UUID travelerId, String firestoreConversationId) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.firestoreConversationId = firestoreConversationId;
    }

    /** Conversation voyageur ↔ destinataire : {@code sender_id} porte le destinataire. */
    public static ConversationEntity forRecipient(UUID bidId, UUID recipientId, UUID travelerId,
                                                  String firestoreConversationId) {
        ConversationEntity c = new ConversationEntity(bidId, recipientId, travelerId, firestoreConversationId);
        c.kind = ConversationKind.RECIPIENT_TRAVELER;
        return c;
    }

    public boolean isRecipientConversation() {
        return kind == ConversationKind.RECIPIENT_TRAVELER;
    }

    /**
     * Participant A, celui que porte la colonne {@code sender_id} : l'expéditeur pour
     * SENDER_TRAVELER, le destinataire pour RECIPIENT_TRAVELER.
     */
    public UUID participantAId() {
        return senderId;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    /** Ferme la conversation (idempotent). */
    public void close(LocalDateTime at) {
        if (closedAt == null) {
            closedAt = at;
        }
    }

    public void deleteForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderDeletedAt = LocalDateTime.now(ZoneOffset.UTC);
        } else if (userId.equals(travelerId)) {
            this.travelerDeletedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public void restoreForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderDeletedAt = null;
        } else if (userId.equals(travelerId)) {
            this.travelerDeletedAt = null;
        }
    }

    public boolean isDeletedByUser(UUID userId) {
        if (userId.equals(senderId)) return senderDeletedAt != null;
        if (userId.equals(travelerId)) return travelerDeletedAt != null;
        return false;
    }

    public void archiveForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderArchivedAt = LocalDateTime.now(ZoneOffset.UTC);
        } else if (userId.equals(travelerId)) {
            this.travelerArchivedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public void unarchiveForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderArchivedAt = null;
        } else if (userId.equals(travelerId)) {
            this.travelerArchivedAt = null;
        }
    }

    public boolean isArchivedByUser(UUID userId) {
        if (userId.equals(senderId)) return senderArchivedAt != null;
        if (userId.equals(travelerId)) return travelerArchivedAt != null;
        return false;
    }

    /** Met les notifications de ce fil en sourdine pour {@code userId} (idempotent : garde la date d'origine). */
    public void muteNotificationsForUser(UUID userId) {
        if (userId.equals(senderId)) {
            if (senderNotificationsMutedAt == null) {
                this.senderNotificationsMutedAt = LocalDateTime.now(ZoneOffset.UTC);
            }
        } else if (userId.equals(travelerId)) {
            if (travelerNotificationsMutedAt == null) {
                this.travelerNotificationsMutedAt = LocalDateTime.now(ZoneOffset.UTC);
            }
        }
    }

    public void unmuteNotificationsForUser(UUID userId) {
        if (userId.equals(senderId)) {
            this.senderNotificationsMutedAt = null;
        } else if (userId.equals(travelerId)) {
            this.travelerNotificationsMutedAt = null;
        }
    }

    /** Vrai si {@code userId} est participant et a mis ce fil en sourdine ; faux pour un tiers ou un id nul. */
    public boolean isNotificationsMutedBy(UUID userId) {
        if (userId == null) return false;
        if (userId.equals(senderId)) return senderNotificationsMutedAt != null;
        if (userId.equals(travelerId)) return travelerNotificationsMutedAt != null;
        return false;
    }

    public boolean isReadOnlyFor(UUID userId) {
        // Conversation destinataire fermée : plus personne n'y écrit.
        if (closedAt != null && (userId.equals(senderId) || userId.equals(travelerId))) return true;
        // Read-only when the OTHER party deleted, but current user hasn't
        if (userId.equals(senderId)) return travelerDeletedAt != null && senderDeletedAt == null;
        if (userId.equals(travelerId)) return senderDeletedAt != null && travelerDeletedAt == null;
        return false;
    }

    public UUID getBidId() { return bidId; }
    /**
     * Valeur brute de {@code sender_id}. ATTENTION : pour RECIPIENT_TRAVELER, c'est le
     * destinataire, pas l'expéditeur du bid. Préférer {@link #participantAId()}.
     */
    public UUID getSenderId() { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public String getFirestoreConversationId() { return firestoreConversationId; }
    public LocalDateTime getSenderDeletedAt() { return senderDeletedAt; }
    public LocalDateTime getTravelerDeletedAt() { return travelerDeletedAt; }
    public LocalDateTime getSenderArchivedAt() { return senderArchivedAt; }
    public LocalDateTime getTravelerArchivedAt() { return travelerArchivedAt; }
    public LocalDateTime getSenderNotificationsMutedAt() { return senderNotificationsMutedAt; }
    public LocalDateTime getTravelerNotificationsMutedAt() { return travelerNotificationsMutedAt; }
    public ConversationKind getKind() { return kind; }
    public LocalDateTime getClosedAt() { return closedAt; }
}

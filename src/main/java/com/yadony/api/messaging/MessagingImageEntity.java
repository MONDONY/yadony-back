package com.yadony.api.messaging;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Photo envoyée dans une conversation (FLUTTER-B4, V295).
 *
 * <p>Le message est dans Firestore ; cette ligne porte les clés R2 de la photo et de sa
 * miniature. Elle est la seule source de ces clés pour l'endpoint de lecture et la purge :
 * le document Firestore reste modifiable par un client, la base non.
 *
 * <p>{@code purgedAt} non nul : les objets R2 ont été effacés (fin de conservation ou
 * suppression de la conversation par les deux parties). Le message Firestore reste, l'app
 * affiche « Photo expirée ».
 */
@Entity
@Table(name = "messaging_images")
@Where(clause = "deleted_at IS NULL")
public class MessagingImageEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "firestore_message_id", nullable = false, length = 40)
    private String firestoreMessageId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "image_key", nullable = false)
    private String imageKey;

    @Column(name = "thumb_key", nullable = false)
    private String thumbKey;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    protected MessagingImageEntity() {}

    public MessagingImageEntity(UUID conversationId, UUID bidId, String firestoreMessageId, UUID senderId,
                                String imageKey, String thumbKey) {
        this.conversationId = conversationId;
        this.bidId = bidId;
        this.firestoreMessageId = firestoreMessageId;
        this.senderId = senderId;
        this.imageKey = imageKey;
        this.thumbKey = thumbKey;
    }

    public boolean isPurged() {
        return purgedAt != null;
    }

    /** Idempotent : garde la date de la première purge. */
    public void markPurged(LocalDateTime at) {
        if (purgedAt == null) {
            purgedAt = at;
        }
    }

    public UUID getConversationId() { return conversationId; }
    public UUID getBidId() { return bidId; }
    public String getFirestoreMessageId() { return firestoreMessageId; }
    public UUID getSenderId() { return senderId; }
    public String getImageKey() { return imageKey; }
    public String getThumbKey() { return thumbKey; }
    public LocalDateTime getPurgedAt() { return purgedAt; }
}

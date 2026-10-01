package com.yadony.api.admin.dto;

import com.yadony.api.messaging.ConversationEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Conversation vue du back-office. `id` = identifiant Firestore : c'est lui
 * que le front réutilise pour lister/supprimer les messages.
 */
public record AdminConversationResponse(
        String id,
        UUID bidId,
        String participantA,
        String participantB,
        String lastMessageAt,
        int messageCount,
        boolean flagged,
        LocalDateTime createdAt,
        /**
         * "SENDER_TRAVELER" (participantA = expéditeur) ou "RECIPIENT_TRAVELER"
         * (participantA = destinataire du colis, lot 3C).
         */
        String kind,
        /** Conversation destinataire fermée après un changement de destinataire. */
        LocalDateTime closedAt
) {
    public static AdminConversationResponse from(ConversationEntity e,
                                                 String participantAName,
                                                 String travelerName,
                                                 String lastMessageAt) {
        return new AdminConversationResponse(
                e.getFirestoreConversationId(),
                e.getBidId(),
                participantAName,
                travelerName,
                lastMessageAt,
                0,
                false,
                e.getCreatedAt(),
                e.getKind() != null ? e.getKind().name() : null,
                e.getClosedAt()
        );
    }
}

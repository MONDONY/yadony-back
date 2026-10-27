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
        LocalDateTime closedAt,
        /**
         * Identifiant interne ({@code conversations.id}). C'est LUI que porte {@code target_id} d'un
         * signalement de message : l'identifiant Firestore ({@code id}) n'y figure jamais.
         */
        UUID conversationId,
        /** Comptes des deux participants (mêmes rôles que {@code participantA}/{@code participantB}). */
        UUID participantAId,
        UUID participantBId,
        /** Signalements ouverts visant un message de cette conversation ; {@code flagged} en dépend. */
        int openReportCount,
        /** Aperçu du dernier message, tel que l'app l'écrit dans Firestore ; null s'il est absent. */
        String lastMessagePreview
) {
    public static AdminConversationResponse from(ConversationEntity e,
                                                 String participantAName,
                                                 String travelerName,
                                                 String lastMessageAt,
                                                 String lastMessagePreview,
                                                 int openReportCount) {
        return new AdminConversationResponse(
                e.getFirestoreConversationId(),
                e.getBidId(),
                participantAName,
                travelerName,
                lastMessageAt,
                0,
                openReportCount > 0,
                e.getCreatedAt(),
                e.getKind() != null ? e.getKind().name() : null,
                e.getClosedAt(),
                e.getId(),
                e.participantAId(),
                e.getTravelerId(),
                openReportCount,
                lastMessagePreview
        );
    }
}

package com.yadony.api.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID bidId,
        String firestoreConversationId,
        ParticipantDTO otherParticipant,
        String lastMessagePreview,
        LocalDateTime lastMessageAt,
        boolean hasUnread,
        // Trip fields — null until bid+announcement are available
        String tripOrigin,
        String tripDestination,
        String tripDate,
        Double tripWeightKg,
        String bidStatus,
        // True when the other party deleted: current user sees history but cannot send
        boolean readOnly,
        // True when the current user deleted their own copy (restorable via /restore)
        boolean deletedBySelf,
        // "SENDER_TRAVELER" | "RECIPIENT_TRAVELER" (lot 3C). Absent chez un ancien back :
        // l'app retombe sur SENDER_TRAVELER.
        String kind,
        // Rôle de l'appelant dans une conversation RECIPIENT_TRAVELER : "TRAVELER" ou
        // "RECIPIENT" ; null pour SENDER_TRAVELER. Code stable, contrairement au libellé
        // localisé de otherParticipant.role.
        String viewerRole
) {
    /** Conversation expéditeur ↔ voyageur : forme d'avant le lot 3C. */
    public ConversationResponse(UUID id, UUID bidId, String firestoreConversationId,
                                ParticipantDTO otherParticipant, String lastMessagePreview,
                                LocalDateTime lastMessageAt, boolean hasUnread, String tripOrigin,
                                String tripDestination, String tripDate, Double tripWeightKg,
                                String bidStatus, boolean readOnly, boolean deletedBySelf) {
        this(id, bidId, firestoreConversationId, otherParticipant, lastMessagePreview, lastMessageAt,
                hasUnread, tripOrigin, tripDestination, tripDate, tripWeightKg, bidStatus, readOnly,
                deletedBySelf, "SENDER_TRAVELER", null);
    }
}

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
        String viewerRole,
        // Appel audio in-app possible maintenant (règle d'éligibilité de calls/). Absent chez
        // un ancien back : l'app masque le bouton.
        boolean callAvailable,
        // Sourdine propre à l'appelant (FLUTTER-CM) : plus de push pour ce fil, non-lus
        // toujours comptés. Jamais exposé à l'autre participant. Absent chez un ancien back :
        // l'app retombe sur false.
        boolean notificationsMuted
) {
    /** Forme d'avant la sourdine (FLUTTER-CM) : non mise en sourdine. */
    public ConversationResponse(UUID id, UUID bidId, String firestoreConversationId,
                                ParticipantDTO otherParticipant, String lastMessagePreview,
                                LocalDateTime lastMessageAt, boolean hasUnread, String tripOrigin,
                                String tripDestination, String tripDate, Double tripWeightKg,
                                String bidStatus, boolean readOnly, boolean deletedBySelf,
                                String kind, String viewerRole, boolean callAvailable) {
        this(id, bidId, firestoreConversationId, otherParticipant, lastMessagePreview, lastMessageAt,
                hasUnread, tripOrigin, tripDestination, tripDate, tripWeightKg, bidStatus, readOnly,
                deletedBySelf, kind, viewerRole, callAvailable, false);
    }

    public ConversationResponse(UUID id, UUID bidId, String firestoreConversationId,
                                ParticipantDTO otherParticipant, String lastMessagePreview,
                                LocalDateTime lastMessageAt, boolean hasUnread, String tripOrigin,
                                String tripDestination, String tripDate, Double tripWeightKg,
                                String bidStatus, boolean readOnly, boolean deletedBySelf,
                                String kind, String viewerRole) {
        this(id, bidId, firestoreConversationId, otherParticipant, lastMessagePreview, lastMessageAt,
                hasUnread, tripOrigin, tripDestination, tripDate, tripWeightKg, bidStatus, readOnly,
                deletedBySelf, kind, viewerRole, false);
    }

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

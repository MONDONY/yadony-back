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
        boolean notificationsMuted,
        // Photos permises maintenant (FLUTTER-B4, ConversationMediaPolicy) : bid accepté et
        // payé, jusqu'à livraison + 3 jours. Absent chez un ancien back : l'app retombe sur
        // false (trombone masqué/grisé).
        boolean mediaAllowed,
        // Statut brut du colis (nom de BidStatus : PENDING, ACCEPTED, HANDED_OVER, IN_TRANSIT,
        // ARRIVED, COMPLETED, CANCELLED…), pour le badge d'état de la liste (FLUTTER-EZ).
        // Distinct de bidStatus, code dérivé que l'app utilise pour ses filtres et bandeaux.
        // Null sans bid. Absent chez un ancien back : l'app n'affiche pas de badge.
        String parcelStatus,
        // Retour du colis à l'expéditeur en cours : annulation après remise, délai de retour
        // posé et colis pas encore rendu (même règle que BidModel.isAwaitingReturn côté app).
        // Absent chez un ancien back : l'app retombe sur false.
        boolean returnPending
) {
    /** Forme d'avant le statut du colis (FLUTTER-EZ) : pas de statut, pas de retour. */
    public ConversationResponse(UUID id, UUID bidId, String firestoreConversationId,
                                ParticipantDTO otherParticipant, String lastMessagePreview,
                                LocalDateTime lastMessageAt, boolean hasUnread, String tripOrigin,
                                String tripDestination, String tripDate, Double tripWeightKg,
                                String bidStatus, boolean readOnly, boolean deletedBySelf,
                                String kind, String viewerRole, boolean callAvailable,
                                boolean notificationsMuted, boolean mediaAllowed) {
        this(id, bidId, firestoreConversationId, otherParticipant, lastMessagePreview, lastMessageAt,
                hasUnread, tripOrigin, tripDestination, tripDate, tripWeightKg, bidStatus, readOnly,
                deletedBySelf, kind, viewerRole, callAvailable, notificationsMuted, mediaAllowed,
                null, false);
    }

    /** Copie portant l'état du colis (FLUTTER-EZ), lu sur le bid déjà chargé. */
    public ConversationResponse withParcelState(String parcelStatus, boolean returnPending) {
        return new ConversationResponse(id, bidId, firestoreConversationId, otherParticipant,
                lastMessagePreview, lastMessageAt, hasUnread, tripOrigin, tripDestination, tripDate,
                tripWeightKg, bidStatus, readOnly, deletedBySelf, kind, viewerRole, callAvailable,
                notificationsMuted, mediaAllowed, parcelStatus, returnPending);
    }

    /** Forme d'avant les photos (FLUTTER-B4) : photos non permises. */
    public ConversationResponse(UUID id, UUID bidId, String firestoreConversationId,
                                ParticipantDTO otherParticipant, String lastMessagePreview,
                                LocalDateTime lastMessageAt, boolean hasUnread, String tripOrigin,
                                String tripDestination, String tripDate, Double tripWeightKg,
                                String bidStatus, boolean readOnly, boolean deletedBySelf,
                                String kind, String viewerRole, boolean callAvailable,
                                boolean notificationsMuted) {
        this(id, bidId, firestoreConversationId, otherParticipant, lastMessagePreview, lastMessageAt,
                hasUnread, tripOrigin, tripDestination, tripDate, tripWeightKg, bidStatus, readOnly,
                deletedBySelf, kind, viewerRole, callAvailable, notificationsMuted, false);
    }

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

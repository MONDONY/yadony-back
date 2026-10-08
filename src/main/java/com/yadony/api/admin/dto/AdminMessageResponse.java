package com.yadony.api.admin.dto;

/**
 * Message Firestore vu du back-office. `createdAt` = sentAt ISO-8601 tel
 * que stocké côté Firestore.
 *
 * <p>{@code deletedAt} : horodatage ISO-8601 de la suppression, {@code null} si le message est
 * visible. {@code deletedByAdmin} : seul le serveur (modération admin) écrit {@code deletedAt}
 * sur un message, les règles Firestore l'interdisent aux clients ; il vaut donc
 * {@code deleted} tant qu'aucun autre chemin de suppression n'existe.
 *
 * <p>Photos (FLUTTER-B4) : {@code type} = {@code TEXT}, {@code IMAGE} ou {@code SYSTEM} ;
 * {@code imageUrl}/{@code thumbUrl} sont des URL signées de 15 minutes, nulles hors photo ou
 * quand la photo a été purgée. L'admin voit aussi les photos des messages supprimés.
 */
public record AdminMessageResponse(
        String id,
        String conversationId,
        String senderName,
        String content,
        boolean flagged,
        boolean deleted,
        String createdAt,
        String deletedAt,
        boolean deletedByAdmin,
        String type,
        String imageUrl,
        String thumbUrl
) {
    /** Forme d'avant les photos : message texte, sans image. */
    public AdminMessageResponse(String id, String conversationId, String senderName, String content,
                                boolean flagged, boolean deleted, String createdAt, String deletedAt,
                                boolean deletedByAdmin) {
        this(id, conversationId, senderName, content, flagged, deleted, createdAt, deletedAt, deletedByAdmin,
                "TEXT", null, null);
    }
}

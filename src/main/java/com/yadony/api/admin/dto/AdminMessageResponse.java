package com.yadony.api.admin.dto;

/**
 * Message Firestore vu du back-office. `createdAt` = sentAt ISO-8601 tel
 * que stocké côté Firestore.
 *
 * <p>{@code deletedAt} : horodatage ISO-8601 de la suppression, {@code null} si le message est
 * visible. {@code deletedByAdmin} : seul le serveur (modération admin) écrit {@code deletedAt}
 * sur un message, les règles Firestore l'interdisent aux clients ; il vaut donc
 * {@code deleted} tant qu'aucun autre chemin de suppression n'existe.
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
        boolean deletedByAdmin
) {
}

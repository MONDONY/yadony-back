package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.FirestoreService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Suppression d'un message par l'équipe : seul chemin, partagé par la modération des
 * conversations et par la résolution d'un signalement de message.
 *
 * <p>La suppression est douce ({@code deletedAt} posé sur le document Firestore) : le corps
 * reste, d'où la restauration possible depuis {@code AdminConversationController}.
 */
@Service
public class AdminMessageModerationService {

    private final FirestoreService firestoreService;
    private final AuditService auditService;

    public AdminMessageModerationService(FirestoreService firestoreService, AuditService auditService) {
        this.firestoreService = firestoreService;
        this.auditService = auditService;
    }

    public void deleteMessage(ConversationEntity conversation, String messageId, UUID adminId) {
        deleteMessage(conversation, messageId, adminId, null);
    }

    /**
     * @param reason motif saisi par l'administrateur ; gravé dans l'audit s'il est renseigné (la
     *               résolution d'un signalement n'en fournit pas : elle porte déjà sa propre note)
     */
    public void deleteMessage(ConversationEntity conversation, String messageId, UUID adminId, String reason) {
        String conversationId = conversation.getFirestoreConversationId();
        firestoreService.softDeleteMessage(conversationId, messageId);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messageId", messageId);
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason.strip());
        }
        auditService.log("message", conversation.getId(), "MESSAGE_ADMIN_DELETED", adminId, payload);
    }
}

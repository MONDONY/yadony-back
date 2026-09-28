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
        String conversationId = conversation.getFirestoreConversationId();
        firestoreService.softDeleteMessage(conversationId, messageId);
        auditService.log("message", conversation.getId(), "MESSAGE_ADMIN_DELETED", adminId,
                Map.of("conversationId", conversationId, "messageId", messageId));
    }
}

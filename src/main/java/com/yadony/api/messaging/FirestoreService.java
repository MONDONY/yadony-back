package com.yadony.api.messaging;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class FirestoreService {

    private static final Logger log = LoggerFactory.getLogger(FirestoreService.class);

    @Nullable
    private final Firestore firestore;

    public FirestoreService(@Nullable Firestore firestore) {
        this.firestore = firestore;
    }

    public void createConversation(String conversationId, Map<String, Object> data) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping createConversation");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId).set(data).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore createConversation failed", e);
        }
    }

    /**
     * Le document parent existe-t-il ? Une sous-collection {@code messages} peut
     * très bien vivre sous un document absent : Firestore l'autorise, et c'est
     * exactement l'état dans lequel se trouvaient les conversations créées pendant
     * que le bean Firestore était nul.
     *
     * <p>Renvoie {@code true} quand Firestore est indisponible, afin qu'un appelant
     * ne tente pas une réparation qui ne pourrait de toute façon pas aboutir.
     */
    public boolean conversationExists(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — assuming conversation {} exists", conversationId);
            return true;
        }
        try {
            return firestore.collection("conversations").document(conversationId).get().get().exists();
        } catch (Exception e) {
            log.warn("Firestore conversationExists failed for {}: {}", conversationId, e.getMessage());
            return true;
        }
    }

    public void addSystemMessage(String conversationId, String body) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping addSystemMessage");
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("senderId", "SYSTEM");
            msg.put("body", body);
            msg.put("imageUrl", null);
            msg.put("type", "SYSTEM");
            msg.put("sentAt", Instant.now().toString());
            msg.put("readAt", null);
            firestore.collection("conversations").document(conversationId)
                     .collection("messages").add(msg).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore addSystemMessage failed", e);
        }
    }

    /** Messages d'une conversation, ordonnés par date d'envoi (lecture admin). */
    public java.util.List<Map<String, Object>> listMessages(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — listMessages returns empty");
            return java.util.List.of();
        }
        try {
            var docs = firestore.collection("conversations").document(conversationId)
                    .collection("messages").orderBy("sentAt").get().get().getDocuments();
            java.util.List<Map<String, Object>> result = new java.util.ArrayList<>(docs.size());
            for (var doc : docs) {
                Map<String, Object> data = new HashMap<>(doc.getData());
                data.put("id", doc.getId());
                result.add(data);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Firestore listMessages failed", e);
        }
    }

    /** lastMessageAt/lastMessagePreview par conversation, en un seul getAll. */
    public Map<String, Map<String, Object>> getConversationMeta(java.util.List<String> conversationIds) {
        if (firestore == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        try {
            var refs = conversationIds.stream()
                    .map(id -> firestore.collection("conversations").document(id))
                    .toArray(com.google.cloud.firestore.DocumentReference[]::new);
            Map<String, Map<String, Object>> result = new HashMap<>();
            for (var snap : firestore.getAll(refs).get()) {
                if (snap.exists() && snap.getData() != null) {
                    result.put(snap.getId(), snap.getData());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Firestore getConversationMeta failed: {}", e.getMessage());
            return Map.of();
        }
    }

    public void updateLastMessage(String conversationId, String preview, String sentAt) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping updateLastMessage");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .update("lastMessagePreview", preview, "lastMessageAt", sentAt).get();
        } catch (Exception e) {
            log.warn("Firestore updateLastMessage failed: {}", e.getMessage());
        }
    }

    public void softDeleteMessage(String conversationId, String messageId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping softDeleteMessage");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .collection("messages").document(messageId)
                     .update("deletedAt", Instant.now().toString()).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore softDeleteMessage failed", e);
        }
    }

    public void clearConversationDeleted(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping clearConversationDeleted");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .update("deletedAt", null).get();
        } catch (Exception e) {
            log.warn("Firestore clearConversationDeleted failed: {}", e.getMessage());
        }
    }

    public void markConversationDeleted(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping markConversationDeleted");
            return;
        }
        try {
            firestore.collection("conversations").document(conversationId)
                     .update("deletedAt", Instant.now().toString()).get();
        } catch (Exception e) {
            log.warn("Firestore markConversationDeleted failed: {}", e.getMessage());
        }
    }

    // Purge définitive : supprime tous les messages puis le document conversation
    public void purgeConversation(String conversationId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping purgeConversation");
            return;
        }
        try {
            var messagesRef = firestore.collection("conversations")
                                       .document(conversationId)
                                       .collection("messages");
            var messages = messagesRef.get().get();
            for (var doc : messages.getDocuments()) {
                doc.getReference().delete().get();
            }
            firestore.collection("conversations").document(conversationId).delete().get();
        } catch (Exception e) {
            log.warn("Firestore purgeConversation failed for {}: {}", conversationId, e.getMessage());
        }
    }

    /**
     * Publie l'état de coupure de messagerie dans Firestore.
     *
     * <p>La règle de sécurité Firestore lit le document {@code moderation/{firebaseUid}}
     * pour refuser l'écriture d'un message : c'est le seul point d'application réel, les
     * clients écrivant directement dans Firestore sans passer par ce backend. Keyer sur
     * l'UUID PostgreSQL rendrait le mute inopérant — la règle ne voit que
     * {@code request.auth.uid}, l'UID Firebase.
     *
     * <p>{@code messagingMutedUntil} est écrit comme un {@link Timestamp} Firestore, jamais
     * une chaîne ISO comme le reste de ce fichier (sentAt, deletedAt, lastMessageAt) : la
     * règle est fail-open sur le type, une chaîne laisserait le mute silencieusement inopérant.
     */
    public void setMessagingMute(String firebaseUid, Instant until) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping setMessagingMute for {}", firebaseUid);
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("messagingMutedUntil", Timestamp.of(Date.from(until)));
            firestore.collection("moderation").document(firebaseUid).set(data).get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore setMessagingMute failed", e);
        }
    }

    /** Retire la coupure — le document est supprimé, pas laissé avec une date passée. */
    public void clearMessagingMute(String firebaseUid) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping clearMessagingMute for {}", firebaseUid);
            return;
        }
        try {
            firestore.collection("moderation").document(firebaseUid).delete().get();
        } catch (Exception e) {
            throw new RuntimeException("Firestore clearMessagingMute failed", e);
        }
    }

    public void anonymizeUser(String userId) {
        if (firestore == null) {
            log.warn("Firestore disabled — skipping anonymizeUser for userId={}", userId);
            return;
        }
        try {
            for (var doc : firestore.collection("conversations")
                    .whereEqualTo("senderId", userId).get().get().getDocuments()) {
                doc.getReference().update(
                        "senderName", "Utilisateur supprimé",
                        "senderFcmToken", null).get();
            }

            for (var doc : firestore.collection("conversations")
                    .whereEqualTo("travelerId", userId).get().get().getDocuments()) {
                doc.getReference().update(
                        "travelerName", "Utilisateur supprimé",
                        "travelerFcmToken", null).get();
            }

            log.info("Firestore user data anonymized for userId={}", userId);
        } catch (Exception e) {
            log.warn("Firestore anonymizeUser failed for {}: {}", userId, e.getMessage());
        }
    }
}

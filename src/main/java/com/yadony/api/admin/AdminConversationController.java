package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminConversationResponse;
import com.yadony.api.admin.dto.AdminMessageResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.messaging.SystemMessages;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.messaging.FirestoreService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConversationController {

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final AuditService auditService;
    private final UserRepository userRepository;

    public AdminConversationController(ConversationRepository conversationRepository,
                                       FirestoreService firestoreService,
                                       AuditService auditService,
                                       UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    @PreAuthorize("hasAuthority('MODERATION_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminConversationResponse>> listAllConversations(
            @RequestParam(required = false) Boolean flagged,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Aucun mecanisme de conversation signalee cote back pour l'instant :
        // le filtre flagged=true renvoie une page vide plutot que de mentir.
        if (Boolean.TRUE.equals(flagged)) {
            return ResponseEntity.ok(Page.empty(PageRequest.of(page, size)));
        }

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ConversationEntity> conversations = conversationRepository.findAll(pageable);

        // Batch : noms des participants + lastMessageAt Firestore (un seul getAll).
        Set<UUID> userIds = new HashSet<>();
        for (ConversationEntity c : conversations.getContent()) {
            if (c.getSenderId() != null) userIds.add(c.getSenderId());
            if (c.getTravelerId() != null) userIds.add(c.getTravelerId());
        }
        Map<UUID, UserEntity> usersById = userRepository.findAllById(userIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        Map<String, Map<String, Object>> meta = firestoreService.getConversationMeta(
                conversations.getContent().stream()
                        .map(ConversationEntity::getFirestoreConversationId)
                        .filter(Objects::nonNull)
                        .toList());

        Page<AdminConversationResponse> result = conversations.map(c -> AdminConversationResponse.from(
                c,
                userName(c.getSenderId(), usersById),
                userName(c.getTravelerId(), usersById),
                lastMessageAt(meta.get(c.getFirestoreConversationId()))));
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAuthority('MODERATION_VIEW')")
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<AdminMessageResponse>> getMessages(@PathVariable String conversationId) {
        conversationRepository.findByFirestoreConversationId(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        List<Map<String, Object>> raw = firestoreService.listMessages(conversationId);

        // Resolution des noms d'expediteur en un batch (senderId = UUID ou "SYSTEM").
        Set<UUID> senderIds = raw.stream()
                .map(m -> parseUuid((String) m.get("senderId")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserEntity> usersById = userRepository.findAllById(senderIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        List<AdminMessageResponse> messages = raw.stream()
                .map(m -> new AdminMessageResponse(
                        (String) m.get("id"),
                        conversationId,
                        senderName((String) m.get("senderId"), usersById),
                        (String) m.getOrDefault("body", ""),
                        false,
                        m.get("deletedAt") != null,
                        (String) m.get("sentAt")))
                .toList();
        return ResponseEntity.ok(messages);
    }

    @PreAuthorize("hasAuthority('MESSAGE_DELETE')")
    @DeleteMapping("/{conversationId}/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable String conversationId,
            @PathVariable String messageId,
            @AuthenticationPrincipal UserEntity admin) {

        ConversationEntity conv = conversationRepository
                .findByFirestoreConversationId(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        firestoreService.softDeleteMessage(conversationId, messageId);

        auditService.log("message", conv.getId(), "MESSAGE_ADMIN_DELETED",
                admin != null ? admin.getId() : null,
                Map.of("conversationId", conversationId, "messageId", messageId));

        return ResponseEntity.noContent().build();
    }

    // ---- Helpers -------------------------------------------------------------

    private static String userName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) return null;
        UserEntity u = users.get(userId);
        return u != null ? MatchingTextUtil.buildName(u) : null;
    }

    private static String senderName(String senderId, Map<UUID, UserEntity> users) {
        if (senderId == null) return null;
        if (SystemMessages.isSystemSender(senderId)) return "Systeme";
        UUID id = parseUuid(senderId);
        if (id == null) return senderId;
        UserEntity u = users.get(id);
        return u != null ? MatchingTextUtil.buildName(u) : null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || "SYSTEM".equals(value)) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String lastMessageAt(Map<String, Object> meta) {
        if (meta == null) return null;
        Object v = meta.get("lastMessageAt");
        return v != null ? v.toString() : null;
    }
}

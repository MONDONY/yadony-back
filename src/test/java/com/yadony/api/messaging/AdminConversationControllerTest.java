package com.yadony.api.messaging;

import com.yadony.api.admin.AdminConversationController;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminConversationControllerTest {

    @Mock ConversationRepository repo;
    @Mock FirestoreService firestoreService;
    @Mock AuditService auditService;
    @Mock com.yadony.api.auth.UserRepository userRepository;

    AdminConversationController controller;

    static final UUID ADMIN_ID = UUID.randomUUID();

    static org.springframework.security.core.Authentication adminAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.yadony.api.admin.account.AdminPrincipal(ADMIN_ID, "admin@yadony.test",
                        com.yadony.api.admin.account.AdminRole.SUPPORT, false, "uid-admin"),
                null, java.util.List.of());
    }

    @BeforeEach
    void setUp() {
        controller = new AdminConversationController(repo, firestoreService, auditService, userRepository,
                new com.yadony.api.admin.AdminMessageModerationService(firestoreService, auditService));
    }

    @Test
    void deleteMessage_callsSoftDeleteAndAudit() {
        UUID bidId = UUID.randomUUID();
        var conv = new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv_test");
        when(repo.findByFirestoreConversationId("conv_test")).thenReturn(Optional.of(conv));

        controller.deleteMessage("conv_test", "msg_001", adminAuth());

        verify(firestoreService).softDeleteMessage("conv_test", "msg_001");
        // L'acteur est l'admin authentifie (AdminPrincipal), jamais null.
        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), eq(ADMIN_ID), anyMap());
    }

    @Test
    void deleteMessage_returns404_whenConversationNotFound() {
        when(repo.findByFirestoreConversationId("conv_unknown")).thenReturn(Optional.empty());

        try {
            controller.deleteMessage("conv_unknown", "msg_001", adminAuth());
            throw new AssertionError("Expected exception");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assert e.getStatusCode().value() == 404;
        }
    }

    @Test
    void getMessages_mapsFirestoreDocs_andResolvesSenders() {
        UUID senderId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, UUID.randomUUID(), "conv_1");
        when(repo.findByFirestoreConversationId("conv_1")).thenReturn(Optional.of(conv));

        java.util.Map<String, Object> msg1 = new java.util.HashMap<>();
        msg1.put("id", "m1");
        msg1.put("senderId", senderId.toString());
        msg1.put("body", "Bonjour");
        msg1.put("sentAt", "2026-07-01T10:00:00Z");
        java.util.Map<String, Object> msg2 = new java.util.HashMap<>();
        msg2.put("id", "m2");
        msg2.put("senderId", "SYSTEM");
        msg2.put("body", "Colis remis");
        msg2.put("sentAt", "2026-07-01T11:00:00Z");
        msg2.put("deletedAt", "2026-07-02T09:00:00Z");
        when(firestoreService.listMessages("conv_1")).thenReturn(java.util.List.of(msg1, msg2));

        UserEntity sender = new UserEntity();
        sender.setFirstName("Awa");
        sender.setLastName("Diop");
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(sender));

        var resp = controller.getMessages("conv_1");
        var messages = resp.getBody();

        org.assertj.core.api.Assertions.assertThat(messages).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(messages.get(0).content()).isEqualTo("Bonjour");
        org.assertj.core.api.Assertions.assertThat(messages.get(0).deleted()).isFalse();
        org.assertj.core.api.Assertions.assertThat(messages.get(1).senderName()).isEqualTo("Systeme");
        org.assertj.core.api.Assertions.assertThat(messages.get(1).deleted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(messages.get(0).deletedAt()).isNull();
        org.assertj.core.api.Assertions.assertThat(messages.get(0).deletedByAdmin()).isFalse();
        org.assertj.core.api.Assertions.assertThat(messages.get(1).deletedAt()).isEqualTo("2026-07-02T09:00:00Z");
        // Seul le serveur (moderation admin) ecrit deletedAt sur un message : les regles
        // Firestore l'interdisent aux clients.
        org.assertj.core.api.Assertions.assertThat(messages.get(1).deletedByAdmin()).isTrue();
    }

    // ---- restoreMessage ----

    private static com.yadony.api.admin.dto.RestoreRequest reason() {
        return new com.yadony.api.admin.dto.RestoreRequest("Suppression faite par erreur");
    }

    @Test
    void restoreMessage_restored_auditsAdminAndReason() {
        var conv = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_r");
        when(repo.findByFirestoreConversationId("conv_r")).thenReturn(Optional.of(conv));
        when(firestoreService.restoreMessage("conv_r", "m1"))
                .thenReturn(FirestoreService.MessageRestoreOutcome.RESTORED);

        var resp = controller.restoreMessage("conv_r", "m1", reason(), adminAuth());

        org.assertj.core.api.Assertions.assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_RESTORED"), eq(ADMIN_ID),
                eq(java.util.Map.of("conversationId", "conv_r", "messageId", "m1",
                        "reason", "Suppression faite par erreur")));
    }

    @Test
    void restoreMessage_notDeleted_is409_withoutAudit() {
        var conv = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_r");
        when(repo.findByFirestoreConversationId("conv_r")).thenReturn(Optional.of(conv));
        when(firestoreService.restoreMessage("conv_r", "m1"))
                .thenReturn(FirestoreService.MessageRestoreOutcome.NOT_DELETED);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller.restoreMessage("conv_r", "m1", reason(), adminAuth()));

        org.assertj.core.api.Assertions.assertThat(ex.getStatus().value()).isEqualTo(409);
        org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo("message-not-deleted");
        verifyNoInteractions(auditService);
    }

    @Test
    void restoreMessage_unknownMessage_is404() {
        var conv = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_r");
        when(repo.findByFirestoreConversationId("conv_r")).thenReturn(Optional.of(conv));
        when(firestoreService.restoreMessage("conv_r", "m1"))
                .thenReturn(FirestoreService.MessageRestoreOutcome.NOT_FOUND);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller.restoreMessage("conv_r", "m1", reason(), adminAuth()));

        org.assertj.core.api.Assertions.assertThat(ex.getStatus().value()).isEqualTo(404);
        org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo("message-not-found");
        verifyNoInteractions(auditService);
    }

    @Test
    void restoreMessage_unknownConversation_is404_withoutTouchingFirestore() {
        when(repo.findByFirestoreConversationId("ghost")).thenReturn(Optional.empty());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller.restoreMessage("ghost", "m1", reason(), adminAuth()));

        org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo("conversation-not-found");
        verifyNoInteractions(firestoreService);
    }

    @Test
    void restoreMessage_withoutAdminPrincipal_is403_beforeAnyWrite() {
        var anonymous = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "someone", null, java.util.List.of());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller.restoreMessage("conv_r", "m1", reason(), anonymous));

        org.assertj.core.api.Assertions.assertThat(ex.getStatus().value()).isEqualTo(403);
        verifyNoInteractions(firestoreService);
    }

    @Test
    void getMessages_unknownConversation_throws404() {
        when(repo.findByFirestoreConversationId("ghost")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.getMessages("ghost"));
    }

    @Test
    void listConversations_flaggedTrue_returnsEmptyPage() {
        var resp = controller.listAllConversations(true, 0, 20);
        org.assertj.core.api.Assertions.assertThat(resp.getBody().getTotalElements()).isZero();
        verifyNoInteractions(firestoreService);
    }
}

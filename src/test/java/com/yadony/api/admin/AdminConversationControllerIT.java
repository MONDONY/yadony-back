package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.messaging.FirestoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moderation des messages vue de bout en bout (filtre de securite compris) : l'admin
 * authentifie est un {@link AdminPrincipal}, jamais un {@code UserEntity}. L'audit doit donc
 * le designer comme acteur.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminConversationControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String CONV = "conv_it";
    private static final String MSG = "msg_it";

    @Autowired MockMvc mockMvc;

    @MockitoBean ConversationRepository conversationRepository;
    @MockitoBean FirestoreService firestoreService;
    @MockitoBean AuditService auditService;
    @Autowired UserRepository userRepository;

    static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private ConversationEntity conversation() {
        return new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CONV);
    }

    @Test
    @DisplayName("GET messages — senderId Firestore = UID Firebase : le nom de l'expediteur est resolu")
    void getMessages_senderIdUidFirebase_resolvesSenderName() throws Exception {
        String uid = "fbUid-" + UUID.randomUUID();
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setUsername("awa" + UUID.randomUUID().toString().substring(0, 8));
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        u.setTotalTrips(0);
        userRepository.save(u);
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(conversation()));
        when(firestoreService.listMessages(CONV)).thenReturn(List.of(
                Map.of("id", "m1", "senderId", uid, "body", "bonjour", "sentAt", "2026-10-07T10:00:00Z"),
                Map.of("id", "m2", "senderId", "SYSTEM", "body", "info", "sentAt", "2026-10-07T10:01:00Z")));

        mockMvc.perform(get("/admin/conversations/{c}/messages", CONV)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].senderName").value("Awa Diallo"))
                .andExpect(jsonPath("$[1].senderName").value("Systeme"));
    }

    @Test
    @DisplayName("DELETE message — l'audit MESSAGE_ADMIN_DELETED designe l'admin, pas null")
    void deleteMessage_auditsAdminAsActor() throws Exception {
        ConversationEntity conv = conversation();
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(conv));

        mockMvc.perform(delete("/admin/conversations/{c}/messages/{m}", CONV, MSG)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isNoContent());

        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), eq(ADMIN_ID),
                eq(Map.of("conversationId", CONV, "messageId", MSG)));
    }

    private ConversationEntity persistedLikeConversation() {
        ConversationEntity conv = conversation();
        org.springframework.test.util.ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
        return conv;
    }

    @Test
    @DisplayName("DELETE message ?reason= — le motif est grave dans l'audit")
    void deleteMessage_withReason_isAudited() throws Exception {
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(conversation()));

        mockMvc.perform(delete("/admin/conversations/{c}/messages/{m}", CONV, MSG).param("reason", "propos haineux")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isNoContent());

        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), eq(ADMIN_ID),
                eq(Map.of("conversationId", CONV, "messageId", MSG, "reason", "propos haineux")));
    }

    @Test
    @DisplayName("DELETE message — motif de plus de 500 caracteres : 422 message-reason-invalid, rien n'est supprime")
    void deleteMessage_reasonTooLong_is422() throws Exception {
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(conversation()));

        mockMvc.perform(delete("/admin/conversations/{c}/messages/{m}", CONV, MSG).param("reason", "x".repeat(501))
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("message-reason-invalid"));

        verify(firestoreService, never()).softDeleteMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("GET context — historique, notes, signalements et participants, y compris pour le support")
    void context_returnsTheFourBlocks() throws Exception {
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(persistedLikeConversation()));

        mockMvc.perform(get("/admin/conversations/{c}/context", CONV).with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history").isArray())
                .andExpect(jsonPath("$.notes").isArray())
                .andExpect(jsonPath("$.reports").isArray())
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].role").value("A"))
                .andExpect(jsonPath("$.participants[1].role").value("B"));
    }

    @Test
    @DisplayName("GET context — conversation inconnue : 404 conversation-not-found")
    void context_unknownConversation_is404() throws Exception {
        when(conversationRepository.findByFirestoreConversationId("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/conversations/{c}/context", "ghost").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("conversation-not-found"));
    }

    @Test
    @DisplayName("POST notes — 201 avec l'auteur ; note trop courte : 422 ; conversation inconnue : 404")
    void addNote_createdThenValidated() throws Exception {
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(persistedLikeConversation()));
        when(conversationRepository.findByFirestoreConversationId("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/conversations/{c}/notes", CONV).contentType("application/json")
                        .content("{\"content\":\"  Appel passe, sans reponse  \"}")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Appel passe, sans reponse"))
                .andExpect(jsonPath("$.adminEmail").value("admin@yadony.com"))
                .andExpect(jsonPath("$.id").isString());
        verify(auditService).log(eq("message"), any(), eq("CONVERSATION_NOTE_ADDED"), eq(ADMIN_ID), any());

        mockMvc.perform(post("/admin/conversations/{c}/notes", CONV).contentType("application/json")
                        .content("{\"content\":\"ab\"}").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("conversation-note-invalid"));
        mockMvc.perform(post("/admin/conversations/{c}/notes", CONV).contentType("application/json")
                        .content("{\"content\":\"   \"}").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/conversations/{c}/notes", "ghost").contentType("application/json")
                        .content("{\"content\":\"Une note valide\"}").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("context et notes — sans session : 401 ; compte non administrateur : 403")
    void toolsEndpoints_requireAnAdmin() throws Exception {
        mockMvc.perform(get("/admin/conversations/{c}/context", CONV)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/conversations/{c}/notes", CONV).contentType("application/json").content("{\"content\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
        var user = new UsernamePasswordAuthenticationToken("firebase-user", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
        mockMvc.perform(get("/admin/conversations/{c}/context", CONV).with(authentication(user))).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/conversations/{c}/notes", CONV).contentType("application/json").content("{\"content\":\"abc\"}")
                .with(authentication(user))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET liste — ?flagged=true et ?q= sont acceptes et paginent (plus de page vide figee)")
    void list_acceptsFlaggedAndSearchParameters() throws Exception {
        when(conversationRepository.findForModeration(org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/conversations").param("flagged", "true").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        verify(conversationRepository).findForModeration(eq(true), eq(false), any(), any());
    }

    @Test
    @DisplayName("POST restore — le support (MESSAGE_DELETE) restaure : 204 + audit MESSAGE_ADMIN_RESTORED")
    void restoreMessage_asSupport_returns204AndAudits() throws Exception {
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(conversation()));
        when(firestoreService.restoreMessage(CONV, MSG)).thenReturn(FirestoreService.MessageRestoreOutcome.RESTORED);

        mockMvc.perform(post("/admin/conversations/{c}/messages/{m}/restore", CONV, MSG)
                        .contentType("application/json")
                        .content("{\"reason\":\"Supprime par erreur\"}")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isNoContent());

        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_RESTORED"), eq(ADMIN_ID),
                eq(Map.of("conversationId", CONV, "messageId", MSG, "reason", "Supprime par erreur")));
    }

    @Test
    @DisplayName("POST restore — message non supprime : 409 message-not-deleted en problem+json")
    void restoreMessage_notDeleted_returns409() throws Exception {
        when(conversationRepository.findByFirestoreConversationId(CONV)).thenReturn(Optional.of(conversation()));
        when(firestoreService.restoreMessage(CONV, MSG)).thenReturn(FirestoreService.MessageRestoreOutcome.NOT_DELETED);

        mockMvc.perform(post("/admin/conversations/{c}/messages/{m}/restore", CONV, MSG)
                        .contentType("application/json")
                        .content("{\"reason\":\"Supprime par erreur\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("message-not-deleted"));
    }

    @Test
    @DisplayName("POST restore — motif de moins de 10 caracteres : 422, Firestore intouche")
    void restoreMessage_shortReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/conversations/{c}/messages/{m}/restore", CONV, MSG)
                        .contentType("application/json")
                        .content("{\"reason\":\"court\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verify(firestoreService, never()).restoreMessage(anyString(), anyString());
    }
}

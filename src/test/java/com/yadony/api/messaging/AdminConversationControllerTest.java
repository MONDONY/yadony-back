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
    @Mock MessagingImageRepository imageRepository;
    @Mock com.yadony.api.common.StorageService storageService;
    @Mock com.yadony.api.signalements.ReportRepository reportRepository;

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
                new com.yadony.api.admin.AdminMessageModerationService(firestoreService, auditService),
                imageRepository, storageService, reportRepository);
    }

    @Test
    void deleteMessage_callsSoftDeleteAndAudit() {
        UUID bidId = UUID.randomUUID();
        var conv = new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv_test");
        when(repo.findByFirestoreConversationId("conv_test")).thenReturn(Optional.of(conv));

        controller.deleteMessage("conv_test", "msg_001", null, adminAuth());

        verify(firestoreService).softDeleteMessage("conv_test", "msg_001");
        // L'acteur est l'admin authentifie (AdminPrincipal), jamais null.
        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), eq(ADMIN_ID), anyMap());
    }

    @Test
    void deleteMessage_returns404_whenConversationNotFound() {
        when(repo.findByFirestoreConversationId("conv_unknown")).thenReturn(Optional.empty());

        try {
            controller.deleteMessage("conv_unknown", "msg_001", null, adminAuth());
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

    // ---- Liste de la modération : signalements ouverts, recherche, aperçu ----

    private static ConversationEntity conversation(UUID internalId, UUID a, UUID b, String firestoreId) {
        var c = new ConversationEntity(UUID.randomUUID(), a, b, firestoreId);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", internalId);
        return c;
    }

    private static org.springframework.data.domain.Page<ConversationEntity> pageOf(ConversationEntity... items) {
        return new org.springframework.data.domain.PageImpl<>(java.util.List.of(items));
    }

    @Test
    void listConversations_flaggedTrue_filtreSurLesSignalementsOuverts_etPorteLesCompteurs() {
        UUID internal = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findForModeration(eq(true), eq(false), any(), any())).thenReturn(pageOf(conversation(internal, a, b, "conv_flag")));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(
                userWithUid(a, "uA", "Aicha", "Diallo"), userWithUid(b, "uB", "Moussa", "Traore")));
        when(firestoreService.getConversationMeta(any())).thenReturn(java.util.Map.of("conv_flag",
                java.util.Map.of("lastMessageAt", "2026-10-23T09:52:00Z", "lastMessagePreview", "C'est quoi ce message ?")));
        when(reportRepository.countOpenMessageReports(any())).thenReturn(java.util.List.<Object[]>of(new Object[] {internal, 2L}));

        var body = controller.listAllConversations(true, null, 0, 20).getBody();

        var only = body.getContent().get(0);
        org.assertj.core.api.Assertions.assertThat(only.id()).isEqualTo("conv_flag");
        org.assertj.core.api.Assertions.assertThat(only.conversationId()).isEqualTo(internal);
        org.assertj.core.api.Assertions.assertThat(only.participantAId()).isEqualTo(a);
        org.assertj.core.api.Assertions.assertThat(only.participantBId()).isEqualTo(b);
        org.assertj.core.api.Assertions.assertThat(only.openReportCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(only.flagged()).isTrue();
        org.assertj.core.api.Assertions.assertThat(only.lastMessagePreview()).isEqualTo("C'est quoi ce message ?");
        org.assertj.core.api.Assertions.assertThat(only.participantA()).isEqualTo("Aicha Diallo");
    }

    @Test
    void listConversations_sansFiltre_pasDeSignalement_pasDApercuQuandFirestoreEstMuet() {
        UUID internal = UUID.randomUUID();
        when(repo.findForModeration(eq(false), eq(false), any(), any()))
                .thenReturn(pageOf(conversation(internal, UUID.randomUUID(), UUID.randomUUID(), "conv_calme")));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of());
        when(firestoreService.getConversationMeta(any())).thenReturn(java.util.Map.of());
        when(reportRepository.countOpenMessageReports(any())).thenReturn(java.util.List.of());

        var only = controller.listAllConversations(null, null, 0, 20).getBody().getContent().get(0);

        org.assertj.core.api.Assertions.assertThat(only.flagged()).isFalse();
        org.assertj.core.api.Assertions.assertThat(only.openReportCount()).isZero();
        org.assertj.core.api.Assertions.assertThat(only.lastMessagePreview()).isNull();
    }

    @Test
    void listConversations_aperçuVide_estIgnore() {
        when(repo.findForModeration(eq(false), eq(false), any(), any()))
                .thenReturn(pageOf(conversation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_v")));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of());
        when(firestoreService.getConversationMeta(any())).thenReturn(java.util.Map.of("conv_v",
                java.util.Map.of("lastMessagePreview", "  ")));
        when(reportRepository.countOpenMessageReports(any())).thenReturn(java.util.List.of());

        org.assertj.core.api.Assertions.assertThat(
                controller.listAllConversations(null, null, 0, 20).getBody().getContent().get(0).lastMessagePreview()).isNull();
    }

    @Test
    void listConversations_rechercheParNom_restreintAuxComptesTrouves() {
        UUID matching = UUID.randomUUID();
        when(userRepository.findIdsByNameLike(eq("%aicha%"), any())).thenReturn(java.util.List.of(matching));
        when(repo.findForModeration(eq(false), eq(true), eq(java.util.List.of(matching)), any()))
                .thenReturn(pageOf(conversation(UUID.randomUUID(), matching, UUID.randomUUID(), "conv_s")));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of());
        when(firestoreService.getConversationMeta(any())).thenReturn(java.util.Map.of());
        when(reportRepository.countOpenMessageReports(any())).thenReturn(java.util.List.of());

        var body = controller.listAllConversations(null, "  Aicha ", 0, 20).getBody();

        org.assertj.core.api.Assertions.assertThat(body.getContent()).hasSize(1);
    }

    @Test
    void listConversations_rechercheSansAucunCompte_pageVide_sansLireLesConversations() {
        when(userRepository.findIdsByNameLike(any(), any())).thenReturn(java.util.List.of());

        var body = controller.listAllConversations(null, "zzz", 0, 20).getBody();

        org.assertj.core.api.Assertions.assertThat(body.getTotalElements()).isZero();
        verifyNoInteractions(repo);
        verifyNoInteractions(firestoreService);
    }

    @Test
    void listConversations_rechercheTropCourte_estIgnoree() {
        when(repo.findForModeration(eq(false), eq(false), any(), any())).thenReturn(pageOf());

        controller.listAllConversations(null, "a", 0, 20);

        verify(userRepository, never()).findIdsByNameLike(any(), any());
    }

    @Test
    void listConversations_taillePlafonnee_pageNegativeRamenee() {
        var pageable = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(repo.findForModeration(anyBoolean(), anyBoolean(), any(), pageable.capture())).thenReturn(pageOf());

        controller.listAllConversations(null, null, -3, 5000);

        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    void escapeLike_neguiseLesJokers() {
        org.assertj.core.api.Assertions.assertThat(AdminConversationController.escapeLike("a%b_c\\d"))
                .isEqualTo("a\\%b\\_c\\\\d");
    }

    @Test
    void deleteMessage_leMotifEstGraveDansLAudit_sansEspacesDeBord() {
        var conv = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_m");
        when(repo.findByFirestoreConversationId("conv_m")).thenReturn(Optional.of(conv));

        controller.deleteMessage("conv_m", "msg_9", "  propos haineux  ", adminAuth());

        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), eq(ADMIN_ID),
                eq(java.util.Map.of("conversationId", "conv_m", "messageId", "msg_9", "reason", "propos haineux")));
    }

    @Test
    void deleteMessage_motifVide_nEstPasGrave() {
        var conv = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_m");
        when(repo.findByFirestoreConversationId("conv_m")).thenReturn(Optional.of(conv));

        controller.deleteMessage("conv_m", "msg_9", "   ", adminAuth());

        verify(auditService).log(eq("message"), any(), eq("MESSAGE_ADMIN_DELETED"), eq(ADMIN_ID),
                eq(java.util.Map.of("conversationId", "conv_m", "messageId", "msg_9")));
    }

    @Test
    void deleteMessage_motifTropLong_422_avantToutEffet() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller.deleteMessage("conv_m", "msg_9", "x".repeat(501), adminAuth()));

        org.assertj.core.api.Assertions.assertThat(ex.getStatus().value()).isEqualTo(422);
        org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo("message-reason-invalid");
        verifyNoInteractions(firestoreService);
        verifyNoInteractions(auditService);
    }

    // ---- senderId Firestore = UID Firebase (l'app et le back y écrivent users.firebase_uid) ----

    private static UserEntity userWithUid(UUID id, String uid, String first, String last) {
        UserEntity u = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", id);
        u.setFirebaseUid(uid);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private static java.util.Map<String, Object> msg(String id, String senderId) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("senderId", senderId);
        m.put("body", "bonjour");
        m.put("sentAt", "2026-10-07T10:00:00Z");
        return m;
    }

    @Test
    void getMessages_nommeLExpediteurParUidFirebase_uuidEtSysteme_enLot() {
        UUID legacyId = UUID.randomUUID();
        when(repo.findByFirestoreConversationId("conv_fs")).thenReturn(Optional.of(
                new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_fs")));
        when(firestoreService.listMessages("conv_fs")).thenReturn(java.util.List.of(
                msg("m1", "fbUidAwa"),
                msg("m2", "fbUidAwa"),
                msg("m3", legacyId.toString()),
                msg("m4", "SYSTEM"),
                msg("m5", "fbUidSupprime"),
                msg("m6", null)));
        when(userRepository.findAllByFirebaseUidIn(any()))
                .thenReturn(java.util.List.of(userWithUid(UUID.randomUUID(), "fbUidAwa", "Awa", "Diallo")));
        when(userRepository.findAllById(any()))
                .thenReturn(java.util.List.of(userWithUid(legacyId, "uidLegacy", "Moussa", "Traoré")));

        var messages = controller.getMessages("conv_fs").getBody();

        org.assertj.core.api.Assertions.assertThat(messages)
                .extracting(com.yadony.api.admin.dto.AdminMessageResponse::senderName)
                .containsExactly("Awa Diallo", "Awa Diallo", "Moussa Traoré", "Systeme", null, null);
        verify(userRepository, times(1)).findAllByFirebaseUidIn(any());
        verify(userRepository, times(1)).findAllById(any());
    }

    @Test
    void getMessages_sansMessage_aucuneRequeteUtilisateur() {
        when(repo.findByFirestoreConversationId("conv_fs")).thenReturn(Optional.of(
                new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_fs")));
        when(firestoreService.listMessages("conv_fs")).thenReturn(java.util.List.of());

        org.assertj.core.api.Assertions.assertThat(controller.getMessages("conv_fs").getBody()).isEmpty();
        verifyNoInteractions(userRepository);
    }

    // ── FLUTTER-B4 : photos vues par l'admin ────────────────────────────────────

    @Test
    void getMessages_exposesTypeAndShortSignedUrls_forLivePhotos_evenDeletedOnes() throws Exception {
        var conv = ConversationMediaPolicyTest.withId(
                new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_img"));
        when(repo.findByFirestoreConversationId("conv_img")).thenReturn(Optional.of(conv));

        var live = new MessagingImageEntity(conv.getId(), conv.getBidId(), "img1", UUID.randomUUID(),
                "messaging/conv_img/img1_full.jpg", "messaging/conv_img/img1_thumb.jpg");
        var purged = new MessagingImageEntity(conv.getId(), conv.getBidId(), "img2", UUID.randomUUID(),
                "messaging/conv_img/img2_full.jpg", "messaging/conv_img/img2_thumb.jpg");
        purged.markPurged(java.time.LocalDateTime.now());
        when(imageRepository.findByConversationId(conv.getId())).thenReturn(java.util.List.of(live, purged));
        when(storageService.generatePresignedUrl("messaging/conv_img/img1_full.jpg", AdminConversationController_TTL))
                .thenReturn("https://r2/full?sig");
        when(storageService.generatePresignedUrl("messaging/conv_img/img1_thumb.jpg", AdminConversationController_TTL))
                .thenReturn("https://r2/thumb?sig");

        java.util.Map<String, Object> m1 = new java.util.HashMap<>();
        m1.put("id", "img1");
        m1.put("senderId", "fbUid");
        m1.put("body", null);
        m1.put("type", "IMAGE");
        m1.put("sentAt", "2026-10-07T10:00:00Z");
        m1.put("deletedAt", "2026-10-07T11:00:00Z");
        java.util.Map<String, Object> m2 = new java.util.HashMap<>(m1);
        m2.put("id", "img2");
        m2.remove("deletedAt");
        java.util.Map<String, Object> m3 = new java.util.HashMap<>();
        m3.put("id", "txt");
        m3.put("senderId", "fbUid");
        m3.put("body", "Bonjour");
        m3.put("sentAt", "2026-10-07T12:00:00Z");
        when(firestoreService.listMessages("conv_img")).thenReturn(java.util.List.of(m1, m2, m3));

        var body = controller.getMessages("conv_img").getBody();

        org.assertj.core.api.Assertions.assertThat(body).hasSize(3);
        var a = body.get(0);
        org.assertj.core.api.Assertions.assertThat(a.type()).isEqualTo("IMAGE");
        org.assertj.core.api.Assertions.assertThat(a.deleted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(a.imageUrl()).isEqualTo("https://r2/full?sig");
        org.assertj.core.api.Assertions.assertThat(a.thumbUrl()).isEqualTo("https://r2/thumb?sig");
        var b = body.get(1);
        org.assertj.core.api.Assertions.assertThat(b.type()).isEqualTo("IMAGE");
        org.assertj.core.api.Assertions.assertThat(b.imageUrl()).isNull();
        org.assertj.core.api.Assertions.assertThat(b.thumbUrl()).isNull();
        var c = body.get(2);
        org.assertj.core.api.Assertions.assertThat(c.type()).isEqualTo("TEXT");
        org.assertj.core.api.Assertions.assertThat(c.imageUrl()).isNull();
    }

    @Test
    void getMessages_signingFailure_leavesUrlsNull() throws Exception {
        var conv = ConversationMediaPolicyTest.withId(
                new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_sig"));
        when(repo.findByFirestoreConversationId("conv_sig")).thenReturn(Optional.of(conv));
        when(imageRepository.findByConversationId(conv.getId())).thenReturn(java.util.List.of(
                new MessagingImageEntity(conv.getId(), conv.getBidId(), "img1", UUID.randomUUID(), "k1", "k2")));
        when(storageService.generatePresignedUrl(any(), any())).thenThrow(new RuntimeException("no creds"));
        java.util.Map<String, Object> m1 = new java.util.HashMap<>();
        m1.put("id", "img1");
        m1.put("type", "IMAGE");
        m1.put("senderId", "SYSTEM");
        when(firestoreService.listMessages("conv_sig")).thenReturn(java.util.List.of(m1));

        var only = controller.getMessages("conv_sig").getBody().get(0);
        org.assertj.core.api.Assertions.assertThat(only.imageUrl()).isNull();
        org.assertj.core.api.Assertions.assertThat(only.thumbUrl()).isNull();
    }

    @Test
    void adminMessageResponse_legacyConstructor_isText() {
        var r = new com.yadony.api.admin.dto.AdminMessageResponse("i", "c", "n", "b", false, false, "t", null, false);
        org.assertj.core.api.Assertions.assertThat(r.type()).isEqualTo("TEXT");
        org.assertj.core.api.Assertions.assertThat(r.imageUrl()).isNull();
    }

    private static final java.time.Duration AdminConversationController_TTL = java.time.Duration.ofMinutes(15);
}

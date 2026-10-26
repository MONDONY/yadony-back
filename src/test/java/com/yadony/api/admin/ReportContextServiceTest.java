package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminReportContextResponse;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportContextServiceTest {

    @Mock ReportRepository reportRepo;
    @Mock UserRepository userRepo;
    @Mock ReportTargetResolver targetResolver;
    @Mock FirestoreService firestoreService;
    @Mock AuditLogRepository auditLogRepo;
    @Mock AdminUserRepository adminUserRepo;
    @Mock ReportTargetPreviewBuilder previewBuilder;

    private ReportContextService service;
    private final UUID reportId = UUID.randomUUID();
    private final UUID reporterId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReportContextService(reportRepo, userRepo, targetResolver, firestoreService, auditLogRepo,
                adminUserRepo, previewBuilder);
        lenient().when(auditLogRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(
                eq("REPORT"), eq(reportId), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    private ReportEntity report(ReportTargetType type, UUID targetId) {
        ReportEntity r = new ReportEntity();
        ReflectionTestUtils.setField(r, "id", reportId);
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setReporterId(reporterId);
        r.setReason(ReportReason.HARASSMENT);
        r.setStatus(ReportStatus.OPEN);
        return r;
    }

    private static UserEntity user(UUID id, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "createdAt", LocalDateTime.of(2026, 3, 1, 9, 0));
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private void resolveTo(ReportEntity r, ResolvedReportTarget target) {
        when(targetResolver.resolve(r)).thenReturn(target);
    }

    // ---- signalant ----

    @Test
    void signalant_nomAncienneteEtDecomptes() {
        ReportEntity r = report(ReportTargetType.APP, null);
        resolveTo(r, ResolvedReportTarget.none());
        when(userRepo.findById(reporterId)).thenReturn(Optional.of(user(reporterId, "Awa", "Diallo")));
        when(reportRepo.countByReporterId(reporterId)).thenReturn(4L);
        when(reportRepo.countByReporterIdAndStatus(reporterId, ReportStatus.RESOLVED)).thenReturn(3L);
        when(reportRepo.countByReporterIdAndStatus(reporterId, ReportStatus.DISMISSED)).thenReturn(1L);

        var ctx = service.build(r, true);

        assertThat(ctx.reporter()).isEqualTo(new AdminReportContextResponse.Reporter(
                reporterId, "Awa Diallo", LocalDateTime.of(2026, 3, 1, 9, 0), 4, 3, 1));
    }

    @Test
    void signalantInconnuOuSupprime_null() {
        ReportEntity r = report(ReportTargetType.APP, null);
        resolveTo(r, ResolvedReportTarget.none());
        when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        assertThat(service.build(r, true).reporter()).isNull();

        r.setReporterId(null);
        assertThat(service.build(r, true).reporter()).isNull();
    }

    // ---- personne visée ----

    @Test
    void personneVisee_statutIdentiteEtSignalementsRecus() {
        ReportEntity r = report(ReportTargetType.USER, subjectId);
        UserEntity subject = user(subjectId, "Moussa", "Ba");
        subject.setStatus(UserStatus.SUSPENDED);
        subject.setKycStatus(KycStatus.VERIFIED);
        subject.setCancellationCount(2);
        resolveTo(r, new ResolvedReportTarget(true, false, subject, null, null));
        when(reportRepo.countReceivedByUser(subjectId)).thenReturn(3L);
        when(reportRepo.countReceivedPending(subjectId)).thenReturn(1L);
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        var s = service.build(r, true).subject();

        assertThat(s.userId()).isEqualTo(subjectId);
        assertThat(s.displayName()).isEqualTo("Moussa Ba");
        assertThat(s.accountStatus()).isEqualTo("SUSPENDED");
        assertThat(s.identityVerified()).isTrue();
        assertThat(s.reportsReceived()).isEqualTo(3);
        assertThat(s.openReportsReceived()).isEqualTo(1);
        assertThat(s.cancellationCount()).isEqualTo(2);
    }

    @Test
    void identiteNonVerifiee_faux_etCibleSansAuteur_sansPersonneVisee() {
        ReportEntity r = report(ReportTargetType.USER, subjectId);
        UserEntity subject = user(subjectId, "Moussa", "Ba");
        subject.setKycStatus(KycStatus.PENDING);
        resolveTo(r, new ResolvedReportTarget(true, false, subject, null, null));
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        assertThat(service.build(r, true).subject().identityVerified()).isFalse();

        resolveTo(r, ResolvedReportTarget.none());
        assertThat(service.build(r, true).subject()).isNull();
    }

    // ---- message en contexte ----

    private ConversationEntity conversation() {
        ConversationEntity c = new ConversationEntity();
        ReflectionTestUtils.setField(c, "firestoreConversationId", "conv_1");
        return c;
    }

    private static Map<String, Object> message(String id, String senderId, String body, String sentAt, Object deletedAt) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("senderId", senderId);
        m.put("body", body);
        m.put("sentAt", sentAt);
        if (deletedAt != null) m.put("deletedAt", deletedAt);
        return m;
    }

    @Test
    void messageEnContexte_ordreMarqueSignaleEtAuteurVise() {
        UUID conversationTarget = UUID.randomUUID();
        ReportEntity r = report(ReportTargetType.MESSAGE, conversationTarget);
        UserEntity subject = user(subjectId, "Moussa", "Ba");
        UserEntity other = user(UUID.randomUUID(), "Awa", "Diallo");
        resolveTo(r, new ResolvedReportTarget(true, false, subject, conversation(), "msg_2"));
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        when(firestoreService.listMessagesAround("conv_1", "msg_2", 2, 2)).thenReturn(List.of(
                message("msg_1", "uid-awa", "Je ne peux pas aller au-delà de 8 €.", "2026-10-26T10:12:00Z", null),
                message("msg_2", "uid-moussa", "Réponds-moi ou je te retrouve.", "2026-10-26T10:42:00Z", null),
                message("msg_3", "SYSTEM", "Conversation signalée", "2026-10-26T10:43:00Z", "2026-10-26T11:00:00Z")));
        when(userRepo.findAllByFirebaseUidIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> uids = inv.getArgument(0);
            ReflectionTestUtils.setField(subject, "firebaseUid", "uid-moussa");
            ReflectionTestUtils.setField(other, "firebaseUid", "uid-awa");
            return List.of(subject, other).stream().filter(u -> uids.contains(u.getFirebaseUid())).toList();
        });

        var ctx = service.build(r, true);

        assertThat(ctx.messagesRestricted()).isFalse();
        assertThat(ctx.messages()).extracting(AdminReportContextResponse.ContextMessage::id)
                .containsExactly("msg_1", "msg_2", "msg_3");
        assertThat(ctx.messages()).extracting(AdminReportContextResponse.ContextMessage::flagged)
                .containsExactly(false, true, false);
        assertThat(ctx.messages()).extracting(AdminReportContextResponse.ContextMessage::fromSubject)
                .containsExactly(false, true, false);
        assertThat(ctx.messages()).extracting(AdminReportContextResponse.ContextMessage::senderName)
                .containsExactly("Awa Diallo", "Moussa Ba", "Système");
        assertThat(ctx.messages().get(2).deleted()).isTrue();
        assertThat(ctx.messages().get(1).body()).isEqualTo("Réponds-moi ou je te retrouve.");
    }

    @Test
    void sansMODERATION_VIEW_pasDeMessagesVoisins_etRestriction_signalee() {
        ReportEntity r = report(ReportTargetType.MESSAGE, UUID.randomUUID());
        resolveTo(r, new ResolvedReportTarget(true, false, null, conversation(), "msg_2"));
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        var ctx = service.build(r, false);

        assertThat(ctx.messages()).isNull();
        assertThat(ctx.messagesRestricted()).isTrue();
        verifyNoInteractions(firestoreService);
    }

    @Test
    void messageIntrouvableOuFirestoreMuet_nullSansRestriction() {
        ReportEntity r = report(ReportTargetType.MESSAGE, UUID.randomUUID());
        resolveTo(r, new ResolvedReportTarget(true, false, null, conversation(), "msg_2"));
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        when(firestoreService.listMessagesAround(eq("conv_1"), eq("msg_2"), anyInt(), anyInt())).thenReturn(List.of());

        var ctx = service.build(r, true);

        assertThat(ctx.messages()).isNull();
        assertThat(ctx.messagesRestricted()).isFalse();
    }

    @Test
    void ciblePasMessage_pasDeMessages_pasDeRestriction() {
        ReportEntity r = report(ReportTargetType.USER, subjectId);
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        var ctx = service.build(r, false);

        assertThat(ctx.messages()).isNull();
        assertThat(ctx.messagesRestricted()).isFalse();
        verify(firestoreService, never()).listMessagesAround(any(), any(), anyInt(), anyInt());
    }

    @Test
    void messageNonRetrouve_pasDeLectureFirestore() {
        ReportEntity r = report(ReportTargetType.MESSAGE, UUID.randomUUID());
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        assertThat(service.build(r, true).messages()).isNull();
        verifyNoInteractions(firestoreService);
    }

    // ---- historique ----

    @Test
    void historique_actionDetailActeurEtNote() {
        ReportEntity r = report(ReportTargetType.APP, null);
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        UUID adminId = UUID.randomUUID();
        AuditLogEntity resolved = new AuditLogEntity();
        resolved.setAction("REPORT_RESOLVED");
        resolved.setActorId(adminId);
        resolved.setPayload(Map.of("action", "WARN_AUTHOR", "note", "Premier avertissement"));
        ReflectionTestUtils.setField(resolved, "createdAt", LocalDateTime.of(2026, 10, 26, 11, 0));
        AuditLogEntity restored = new AuditLogEntity();
        restored.setAction("REPORT_RESTORED");
        restored.setPayload(Map.of("reason", "Supprimé par erreur"));
        AuditLogEntity bare = new AuditLogEntity();
        bare.setAction("REPORT_DELETED");
        bare.setPayload(Map.of("note", "  "));
        when(auditLogRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(eq("REPORT"), eq(reportId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(resolved, restored, bare)));
        AdminUserEntity admin = new AdminUserEntity();
        ReflectionTestUtils.setField(admin, "id", adminId);
        admin.setEmail("sira@yadony.com");
        when(adminUserRepo.findAllById(anyCollection())).thenReturn(List.of(admin));

        var history = service.build(r, true).history();

        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isEqualTo(new AdminReportContextResponse.HistoryEntry(
                "REPORT_RESOLVED", "WARN_AUTHOR", LocalDateTime.of(2026, 10, 26, 11, 0), "sira@yadony.com", "Premier avertissement"));
        assertThat(history.get(1).note()).isEqualTo("Supprimé par erreur");
        assertThat(history.get(1).actorEmail()).isNull();
        assertThat(history.get(2).note()).isNull();
    }

    @Test
    void historique_vide_sansRechercheDAdmin() {
        ReportEntity r = report(ReportTargetType.APP, null);
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());

        assertThat(service.build(r, true).history()).isEmpty();
        verifyNoInteractions(adminUserRepo);
    }

    // ---- autres signalements sur la même cible ----

    @Test
    void autresSignalementsSurLaMemeCible_avecLeNomDeLeurSignalant() {
        UUID targetId = UUID.randomUUID();
        ReportEntity r = report(ReportTargetType.ANNOUNCEMENT, targetId);
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        UUID otherReporter = UUID.randomUUID();
        ReportEntity other = new ReportEntity();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        other.setReason(ReportReason.SCAM_ATTEMPT);
        other.setStatus(ReportStatus.RESOLVED);
        other.setReporterId(otherReporter);
        ReflectionTestUtils.setField(other, "createdAt", LocalDateTime.of(2026, 10, 25, 8, 0));
        ReportEntity anonymous = new ReportEntity();
        ReflectionTestUtils.setField(anonymous, "id", UUID.randomUUID());
        anonymous.setReason(ReportReason.SCAM_ATTEMPT);
        anonymous.setStatus(ReportStatus.OPEN);
        when(reportRepo.findTop10ByTargetTypeAndTargetIdAndIdNotOrderByCreatedAtDesc(
                ReportTargetType.ANNOUNCEMENT, targetId, reportId)).thenReturn(List.of(other, anonymous));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(otherReporter, "Fatou", "Kane")));

        var related = service.build(r, true).relatedReports();

        assertThat(related).hasSize(2);
        assertThat(related.get(0).reason()).isEqualTo("SCAM_ATTEMPT");
        assertThat(related.get(0).status()).isEqualTo("RESOLVED");
        assertThat(related.get(0).reporterName()).isEqualTo("Fatou Kane");
        assertThat(related.get(1).reporterName()).isNull();
    }

    @Test
    void autresSignalements_aucunPourLApplicationNiSansCible() {
        ReportEntity app = report(ReportTargetType.APP, null);
        resolveTo(app, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        assertThat(service.build(app, true).relatedReports()).isEmpty();

        ReportEntity none = report(ReportTargetType.USER, subjectId);
        resolveTo(none, ResolvedReportTarget.none());
        when(reportRepo.findTop10ByTargetTypeAndTargetIdAndIdNotOrderByCreatedAtDesc(
                ReportTargetType.USER, subjectId, reportId)).thenReturn(List.of());
        assertThat(service.build(none, true).relatedReports()).isEmpty();
    }

    // ---- faits de la cible ----

    @Test
    void faitsDeLaCible_rendusTelsQuelsParLeConstructeur() {
        ReportEntity r = report(ReportTargetType.ANNOUNCEMENT, UUID.randomUUID());
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        var preview = new AdminReportContextResponse.TargetPreview("ANNOUNCEMENT", "ACTIVE",
                List.of(new AdminReportContextResponse.Fact("Trajet", "Paris → Dakar")), "Texte");
        when(previewBuilder.build(r)).thenReturn(preview);

        assertThat(service.build(r, true).targetPreview()).isSameAs(preview);
    }

    // ---- retours sur le même écran ----

    private ReportEntity screenBug(String route) {
        ReportEntity r = report(ReportTargetType.APP, null);
        r.setReason(ReportReason.SCREEN_BUG);
        r.setScreenRoute(route);
        return r;
    }

    @Test
    void retoursSurLeMemeEcran_decomptesEtDerniersRetours() {
        ReportEntity r = screenBug("/payment/confirm");
        resolveTo(r, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        UUID otherReporter = UUID.randomUUID();
        ReportEntity other = new ReportEntity();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        other.setReason(ReportReason.SCREEN_BUG);
        other.setStatus(ReportStatus.OPEN);
        other.setReporterId(otherReporter);
        ReflectionTestUtils.setField(other, "createdAt", LocalDateTime.of(2026, 10, 25, 8, 0));
        when(reportRepo.countByReasonAndScreenRoute(ReportReason.SCREEN_BUG, "/payment/confirm")).thenReturn(7L);
        when(reportRepo.countByReasonAndScreenRouteAndStatus(ReportReason.SCREEN_BUG, "/payment/confirm", ReportStatus.OPEN))
                .thenReturn(5L);
        when(reportRepo.findTop5ByReasonAndScreenRouteAndIdNotOrderByCreatedAtDesc(
                ReportReason.SCREEN_BUG, "/payment/confirm", reportId)).thenReturn(List.of(other));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(otherReporter, "Fatou", "Kane")));

        var s = service.build(r, true).sameScreen();

        assertThat(s.route()).isEqualTo("/payment/confirm");
        assertThat(s.total()).isEqualTo(7);
        assertThat(s.open()).isEqualTo(5);
        assertThat(s.recent()).hasSize(1);
        assertThat(s.recent().get(0).reporterName()).isEqualTo("Fatou Kane");
    }

    @Test
    void retoursSurLeMemeEcran_rienPourUnAutreMotifOuSansRoute() {
        ReportEntity other = report(ReportTargetType.USER, subjectId);
        resolveTo(other, ResolvedReportTarget.none());
        lenient().when(userRepo.findById(reporterId)).thenReturn(Optional.empty());
        assertThat(service.build(other, true).sameScreen()).isNull();

        for (String route : new String[] {null, "  "}) {
            ReportEntity noRoute = screenBug(route);
            resolveTo(noRoute, ResolvedReportTarget.none());
            assertThat(service.build(noRoute, true).sameScreen()).isNull();
        }
        verify(reportRepo, never()).countByReasonAndScreenRoute(any(), any());
    }

    @Test
    void historiqueDUnAvis_reintegrationDistingueeDeLExclusion() {
        UUID ratingId = UUID.randomUUID();
        AuditLogEntity excluded = new AuditLogEntity();
        excluded.setAction("RATING_EXCLUDED");
        excluded.setPayload(Map.of("reason", "insultes", "excluded", "true"));
        AuditLogEntity reintegrated = new AuditLogEntity();
        reintegrated.setAction("RATING_EXCLUDED");
        reintegrated.setPayload(Map.of("reason", "", "excluded", "false"));
        AuditLogEntity legacy = new AuditLogEntity();
        legacy.setAction("RATING_EXCLUDED");
        legacy.setPayload(Map.of("reason", "ancien"));
        when(auditLogRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(eq("RATING"), eq(ratingId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reintegrated, excluded, legacy)));

        var history = service.history("RATING", ratingId);

        assertThat(history).extracting(AdminReportContextResponse.HistoryEntry::detail)
                .containsExactly("REINTEGRATED", null, null);
        assertThat(history.get(1).note()).isEqualTo("insultes");
        assertThat(service.history("RATING", null)).isEmpty();
    }

    @Test
    void historiqueDUneDeclarationDAbsence_leMotifInterneEstLaNote_pasLeCodeDuMotif() {
        UUID id = UUID.randomUUID();
        AuditLogEntity decided = new AuditLogEntity();
        decided.setAction("NOSHOW_CONFIRMED");
        decided.setPayload(Map.of("reason", "SENDER_NO_SHOW", "decisionReason", "Appel resté sans réponse"));
        when(auditLogRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(eq("CANCELLATION"), eq(id), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(decided)));

        assertThat(service.history("CANCELLATION", id).get(0).note()).isEqualTo("Appel resté sans réponse");
    }

    @Test
    void historiqueDUnTicketSupport_laNouvellePrioriteQualifieLeGeste() {
        UUID id = UUID.randomUUID();
        AuditLogEntity changed = new AuditLogEntity();
        changed.setAction("SUPPORT_TICKET_PRIORITY_CHANGED");
        changed.setPayload(Map.of("previous", "NORMAL", "priority", "URGENT"));
        when(auditLogRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(eq("support_ticket"), eq(id), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(changed)));

        assertThat(service.history("support_ticket", id).get(0).detail()).isEqualTo("URGENT");
    }
}

package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.ConversationContextResponse;
import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminConversationToolsService : contexte et notes internes d'une conversation")
class AdminConversationToolsServiceTest {

    @Mock ConversationAdminNoteRepository notes;
    @Mock ReportContextService reportContext;
    @Mock ReportRepository reports;
    @Mock UserRepository users;
    @Mock AdminUserRepository admins;
    @Mock AuditService auditService;

    private AdminConversationToolsService service;
    private final UUID adminId = UUID.randomUUID();
    private final UUID internalId = UUID.randomUUID();
    private final UUID aId = UUID.randomUUID();
    private final UUID bId = UUID.randomUUID();
    private ConversationEntity conversation;

    @BeforeEach
    void setUp() {
        service = new AdminConversationToolsService(notes, reportContext, reports, users, admins, auditService);
        conversation = new ConversationEntity(UUID.randomUUID(), aId, bId, "conv_fs");
        ReflectionTestUtils.setField(conversation, "id", internalId);
    }

    private static UserEntity user(UUID id, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private static ConversationAdminNoteEntity note(UUID admin, String content) {
        ConversationAdminNoteEntity n = new ConversationAdminNoteEntity();
        ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(n, "createdAt", LocalDateTime.parse("2026-10-24T10:00:00"));
        n.setAdminId(admin);
        n.setContent(content);
        return n;
    }

    private static ReportEntity report(UUID reporter, ReportReason reason, ReportStatus status) {
        ReportEntity r = new ReportEntity();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.parse("2026-10-23T09:58:00"));
        r.setReporterId(reporter);
        r.setReason(reason);
        r.setStatus(status);
        return r;
    }

    @Test
    void context_composeHistorique_notes_signalements_etParticipants() {
        UUID noteAdmin = UUID.randomUUID();
        var history = List.of(new HistoryEntry("MESSAGE_ADMIN_DELETED", null, LocalDateTime.parse("2026-10-25T15:02:00"),
                "sara@yadony.com", "propos haineux"));
        when(reportContext.history("message", internalId)).thenReturn(history);
        when(notes.findByConversationIdOrderByCreatedAtDesc(internalId)).thenReturn(List.of(note(noteAdmin, "Même lien dans 2 conversations")));
        AdminUserEntity admin = new AdminUserEntity("uid", "sira@yadony.com", com.yadony.api.admin.account.AdminRole.SUPPORT);
        ReflectionTestUtils.setField(admin, "id", noteAdmin);
        when(admins.findAllById(any())).thenReturn(List.of(admin));
        when(reports.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.MESSAGE, internalId))
                .thenReturn(List.of(report(aId, ReportReason.SPAM, ReportStatus.OPEN)));
        when(users.findAllById(any())).thenReturn(List.of(user(aId, "Aicha", "Diallo"), user(bId, "Moussa", "Traore")));

        ConversationContextResponse ctx = service.context(conversation);

        assertThat(ctx.history()).isEqualTo(history);
        assertThat(ctx.notes()).singleElement().satisfies(n -> {
            assertThat(n.adminEmail()).isEqualTo("sira@yadony.com");
            assertThat(n.content()).isEqualTo("Même lien dans 2 conversations");
        });
        assertThat(ctx.reports()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo("SPAM");
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.reporterName()).isEqualTo("Aicha Diallo");
        });
        assertThat(ctx.participants()).extracting("userId", "displayName", "role")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(aId, "Aicha Diallo", "A"),
                        org.assertj.core.groups.Tuple.tuple(bId, "Moussa Traore", "B"));
    }

    @Test
    void context_conversationSansRien_listesVides_participantsSansNomSiComptesIntrouvables() {
        when(reportContext.history("message", internalId)).thenReturn(List.of());
        when(notes.findByConversationIdOrderByCreatedAtDesc(internalId)).thenReturn(List.of());
        when(reports.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());

        ConversationContextResponse ctx = service.context(conversation);

        assertThat(ctx.history()).isEmpty();
        assertThat(ctx.notes()).isEmpty();
        assertThat(ctx.reports()).isEmpty();
        assertThat(ctx.participants()).extracting("displayName").containsExactly(null, null);
        assertThat(ctx.participants()).extracting("userId").containsExactly(aId, bId);
    }

    @Test
    void context_auteurDeNoteSupprime_emailNull_signalantInconnu_nomNull() {
        when(reportContext.history(any(), any())).thenReturn(List.of());
        when(notes.findByConversationIdOrderByCreatedAtDesc(internalId)).thenReturn(List.of(note(UUID.randomUUID(), "Note ancienne")));
        when(admins.findAllById(any())).thenReturn(List.of());
        when(reports.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(report(null, null, null), report(UUID.randomUUID(), ReportReason.OTHER, ReportStatus.RESOLVED)));
        when(users.findAllById(any())).thenReturn(List.of());

        ConversationContextResponse ctx = service.context(conversation);

        assertThat(ctx.notes().get(0).adminEmail()).isNull();
        assertThat(ctx.reports()).extracting("reporterName").containsExactly(null, null);
        assertThat(ctx.reports().get(0).reason()).isNull();
        assertThat(ctx.reports().get(0).status()).isNull();
    }

    @Test
    void context_limiteLesSignalementsLus() {
        when(reportContext.history(any(), any())).thenReturn(List.of());
        when(notes.findByConversationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());
        when(reports.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(java.util.stream.IntStream.range(0, 35).mapToObj(i -> report(null, ReportReason.SPAM, ReportStatus.OPEN)).toList());

        assertThat(service.context(conversation).reports()).hasSize(AdminConversationToolsService.REPORTS_LIMIT);
    }

    @Test
    void addNote_enregistre_laNoteTronqueeDesEspaces_etTraceSansLeTexte() {
        when(notes.save(any())).thenAnswer(i -> {
            ConversationAdminNoteEntity n = i.getArgument(0);
            ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
            return n;
        });

        ConversationAdminNoteEntity saved = service.addNote(conversation, adminId, "  Appel passé, sans réponse  ");

        ArgumentCaptor<ConversationAdminNoteEntity> captor = ArgumentCaptor.forClass(ConversationAdminNoteEntity.class);
        verify(notes).save(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo(internalId);
        assertThat(captor.getValue().getAdminId()).isEqualTo(adminId);
        assertThat(saved.getContent()).isEqualTo("Appel passé, sans réponse");
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("message"), eq(internalId), eq("CONVERSATION_NOTE_ADDED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("conversationId", "conv_fs").containsKey("noteId");
        assertThat(payload.getValue().values()).noneMatch(v -> String.valueOf(v).contains("Appel passé"));
    }

    @Test
    void addNote_refuseUneNoteTropCourteOuTropLongue_enSurLeCode() {
        for (String bad : new String[] {null, "", "  ", "ab", " ab ", "x".repeat(1001)}) {
            assertThatThrownBy(() -> service.addNote(conversation, adminId, bad))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getErrorCode()).isEqualTo("conversation-note-invalid");
                    });
        }
        verify(notes, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void addNote_accepteLesBornes() {
        when(notes.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.addNote(conversation, adminId, "abc").getContent()).hasSize(3);
        assertThat(service.addNote(conversation, adminId, "x".repeat(1000)).getContent()).hasSize(1000);
    }
}

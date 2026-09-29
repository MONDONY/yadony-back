package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportPhotoEntity;
import com.yadony.api.signalements.ReportPhotoRepository;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportMessageEntity;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketService;
import com.yadony.api.support.SupportTicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReportReplyServiceTest {

    private static final UUID REPORT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
    private static final UUID NEW_TICKET_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005");

    @Mock ReportRepository reportRepository;
    @Mock ReportPhotoRepository photoRepository;
    @Mock UserRepository userRepository;
    @Mock SupportTicketService supportTicketService;
    @Mock AuditService auditService;

    private AdminReportReplyService service;

    @BeforeEach
    void setUp() {
        service = new AdminReportReplyService(reportRepository, photoRepository, userRepository,
                supportTicketService, auditService, TestMessages.resolver());
    }

    @Test
    void reply_opensATicketWithTheReportContextThenTheAdminReply() {
        ReportEntity report = appReport("L'écran reste blanc", "/profile/edit");
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter(AppLanguage.FR)));
        when(photoRepository.findByReportIdOrderByCreatedAtAsc(REPORT_ID))
                .thenReturn(List.of(photo("reports/" + REPORTER_ID + "/a.png"),
                        photo("reports/" + REPORTER_ID + "/b.jpg")));
        when(supportTicketService.adminStartTicketFromContext(eq(REPORTER_ID), eq(ADMIN_ID), eq("OTHER"),
                anyString(), anyString(), anyList(), eq("Merci, c'est corrigé."), eq(List.of())))
                .thenReturn(ticket(TICKET_ID, SupportTicketStatus.WAITING_USER));

        AdminReportReplyService.Result result = service.reply(REPORT_ID, ADMIN_ID, "Merci, c'est corrigé.", null);

        assertThat(result.created()).isTrue();
        assertThat(result.ticket().getId()).isEqualTo(TICKET_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sourceKeys = ArgumentCaptor.forClass(List.class);
        verify(supportTicketService).adminStartTicketFromContext(eq(REPORTER_ID), eq(ADMIN_ID), eq("OTHER"),
                subject.capture(), context.capture(), sourceKeys.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo("Votre signalement du 28/09/2026");
        assertThat(context.getValue())
                .startsWith("Votre signalement :")
                .contains("L'écran reste blanc")
                .contains("Écran concerné : Profil (/profile/edit)")
                .contains("28/09/2026 à 14:05 UTC");
        assertThat(sourceKeys.getValue()).containsExactly(
                "reports/" + REPORTER_ID + "/a.png", "reports/" + REPORTER_ID + "/b.jpg");

        // Lien posé, statut du signalement inchangé.
        assertThat(report.getSupportTicketId()).isEqualTo(TICKET_ID);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        verify(reportRepository).save(report);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("REPORT"), eq(REPORT_ID), eq("REPORT_REPLIED"), eq(ADMIN_ID), details.capture());
        assertThat(details.getValue())
                .containsEntry("adminId", ADMIN_ID.toString())
                .containsEntry("reportId", REPORT_ID.toString())
                .containsEntry("ticketId", TICKET_ID.toString())
                .containsEntry("created", true);
    }

    @Test
    void reply_writesTheSubjectAndContextInEnglishForAnEnglishReporter() {
        service = new AdminReportReplyService(reportRepository, photoRepository, userRepository,
                supportTicketService, auditService, TestMessages.resolver(AppLanguage.EN));
        ReportEntity report = appReport(null, "/some/unknown-screen");
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter(AppLanguage.EN)));
        when(photoRepository.findByReportIdOrderByCreatedAtAsc(REPORT_ID)).thenReturn(List.of());
        when(supportTicketService.adminStartTicketFromContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ticket(TICKET_ID, SupportTicketStatus.WAITING_USER));

        service.reply(REPORT_ID, ADMIN_ID, "Thanks", List.of("support/admin/" + ADMIN_ID + "/x.png"));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(supportTicketService).adminStartTicketFromContext(eq(REPORTER_ID), eq(ADMIN_ID), eq("OTHER"),
                subject.capture(), context.capture(), eq(List.of()), eq("Thanks"),
                eq(List.of("support/admin/" + ADMIN_ID + "/x.png")));
        assertThat(subject.getValue()).isEqualTo("Your report of Sep 28, 2026");
        assertThat(context.getValue())
                .startsWith("Your report:")
                .contains("(no description)")
                // Route inconnue : rendue brute.
                .contains("Screen: /some/unknown-screen")
                .contains("Sep 28, 2026 at 14:05 UTC");
    }

    @Test
    void reply_withoutScreenRoute_omitsTheScreenLine_andTruncatesAVeryLongDescription() {
        ReportEntity report = appReport("x".repeat(5000), null);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter(AppLanguage.FR)));
        when(photoRepository.findByReportIdOrderByCreatedAtAsc(REPORT_ID)).thenReturn(List.of());
        when(supportTicketService.adminStartTicketFromContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ticket(TICKET_ID, SupportTicketStatus.WAITING_USER));

        service.reply(REPORT_ID, ADMIN_ID, "Merci", null);

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(supportTicketService).adminStartTicketFromContext(any(), any(), any(), any(),
                context.capture(), any(), any(), any());
        assertThat(context.getValue()).doesNotContain("Écran concerné");
        assertThat(context.getValue().length()).isLessThanOrEqualTo(4000);
        assertThat(context.getValue()).contains("…");
    }

    @Test
    void reply_onAnOpenLinkedTicket_addsTheReplyToIt() {
        ReportEntity report = appReport("Bug", "/home");
        report.setSupportTicketId(TICKET_ID);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter(AppLanguage.FR)));
        SupportTicketEntity existing = ticket(TICKET_ID, SupportTicketStatus.WAITING_SUPPORT);
        when(supportTicketService.findTicketForAdmin(TICKET_ID)).thenReturn(Optional.of(existing));
        when(supportTicketService.adminReplyTakingOver(TICKET_ID, ADMIN_ID, "Encore un détail", List.of()))
                .thenReturn(new SupportMessageEntity());

        AdminReportReplyService.Result result = service.reply(REPORT_ID, ADMIN_ID, "Encore un détail", List.of());

        assertThat(result.created()).isFalse();
        assertThat(result.ticket()).isSameAs(existing);
        assertThat(report.getSupportTicketId()).isEqualTo(TICKET_ID);
        verify(supportTicketService, never()).adminStartTicketFromContext(
                any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(photoRepository);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("REPORT"), eq(REPORT_ID), eq("REPORT_REPLIED"), eq(ADMIN_ID), details.capture());
        assertThat(details.getValue()).containsEntry("created", false)
                .containsEntry("ticketId", TICKET_ID.toString());
    }

    @Test
    void reply_onAResolvedLinkedTicket_opensANewTicketAndMovesTheLink() {
        ReportEntity report = appReport("Bug", "/home");
        report.setSupportTicketId(TICKET_ID);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter(AppLanguage.FR)));
        when(supportTicketService.findTicketForAdmin(TICKET_ID))
                .thenReturn(Optional.of(ticket(TICKET_ID, SupportTicketStatus.RESOLVED)));
        when(photoRepository.findByReportIdOrderByCreatedAtAsc(REPORT_ID)).thenReturn(List.of());
        when(supportTicketService.adminStartTicketFromContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ticket(NEW_TICKET_ID, SupportTicketStatus.WAITING_USER));

        AdminReportReplyService.Result result = service.reply(REPORT_ID, ADMIN_ID, "Réponse", null);

        assertThat(result.created()).isTrue();
        assertThat(result.ticket().getId()).isEqualTo(NEW_TICKET_ID);
        assertThat(report.getSupportTicketId()).isEqualTo(NEW_TICKET_ID);
        verify(supportTicketService, never()).adminReplyTakingOver(any(), any(), any(), any());
    }

    @Test
    void reply_whenTheLinkedTicketNoLongerExists_opensANewTicket() {
        ReportEntity report = appReport("Bug", "/home");
        report.setSupportTicketId(TICKET_ID);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter(AppLanguage.FR)));
        when(supportTicketService.findTicketForAdmin(TICKET_ID)).thenReturn(Optional.empty());
        when(photoRepository.findByReportIdOrderByCreatedAtAsc(REPORT_ID)).thenReturn(List.of());
        when(supportTicketService.adminStartTicketFromContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ticket(NEW_TICKET_ID, SupportTicketStatus.WAITING_USER));

        AdminReportReplyService.Result result = service.reply(REPORT_ID, ADMIN_ID, "Réponse", null);

        assertThat(result.created()).isTrue();
        assertThat(report.getSupportTicketId()).isEqualTo(NEW_TICKET_ID);
    }

    @Test
    void reply_unknownReport_is404() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reply(REPORT_ID, ADMIN_ID, "x", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("report-not-found");
                });
    }

    @Test
    void reply_onANonAppReport_is422() {
        ReportEntity report = appReport("Bug", null);
        report.setTargetType(ReportTargetType.USER);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.reply(REPORT_ID, ADMIN_ID, "x", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("report-not-app-bug");
                });
        verifyNoInteractions(supportTicketService, auditService);
    }

    @Test
    void reply_withoutReporter_is422() {
        ReportEntity report = appReport("Bug", null);
        report.setReporterId(null);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.reply(REPORT_ID, ADMIN_ID, "x", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("reporter-unavailable");
                });
    }

    @Test
    void reply_whenTheReporterAccountIsDeleted_is422() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(appReport("Bug", null)));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reply(REPORT_ID, ADMIN_ID, "x", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("reporter-unavailable"));
        verifyNoInteractions(supportTicketService, auditService);
    }

    @Test
    void screenLabel_knownSectionsAreReadable_othersRaw() {
        assertThat(AdminReportReplyService.screenLabel(TestMessages.fr(), "/payments/wallet?x=1"))
                .isEqualTo("Paiements (/payments/wallet?x=1)");
        assertThat(AdminReportReplyService.screenLabel(TestMessages.en(), "/tracking/scan"))
                .isEqualTo("Tracking (/tracking/scan)");
        assertThat(AdminReportReplyService.screenLabel(TestMessages.fr(), "profile"))
                .isEqualTo("Profil (profile)");
        assertThat(AdminReportReplyService.screenLabel(TestMessages.fr(), "/"))
                .isEqualTo("/");
    }

    // ---------------------------------------------------------------- helpers

    private static ReportEntity appReport(String description, String screenRoute) {
        ReportEntity report = new ReportEntity();
        ReflectionTestUtils.setField(report, "id", REPORT_ID);
        ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.of(2026, 9, 28, 14, 5, 30));
        report.setTargetType(ReportTargetType.APP);
        report.setReason(ReportReason.SCREEN_BUG);
        report.setReporterId(REPORTER_ID);
        report.setDescription(description);
        report.setScreenRoute(screenRoute);
        return report;
    }

    private static UserEntity reporter(AppLanguage language) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", REPORTER_ID);
        user.setPreferredLanguage(language);
        return user;
    }

    private static ReportPhotoEntity photo(String key) {
        ReportPhotoEntity photo = new ReportPhotoEntity();
        photo.setReportId(REPORT_ID);
        photo.setObjectKey(key);
        return photo;
    }

    private static SupportTicketEntity ticket(UUID id, SupportTicketStatus status) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ReflectionTestUtils.setField(ticket, "id", id);
        ticket.setUserId(REPORTER_ID);
        ticket.setStatus(status);
        return ticket;
    }
}

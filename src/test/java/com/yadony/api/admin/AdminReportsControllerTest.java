package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminReportResponse;
import com.yadony.api.admin.dto.ResolveReportRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.signalements.ReportAction;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminReportsControllerTest {

    @Mock ReportRepository reportRepo;
    @Mock UserRepository userRepo;
    @Mock AnnouncementRepository announcementRepo;
    @Mock AuditService auditService;
    @Mock com.yadony.api.signalements.ReportService reportService;
    @Mock UserService userService;
    @Mock AnnouncementService announcementService;
    @Mock NotificationDispatcher notificationDispatcher;

    @BeforeEach
    void stubMessages() {
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private AdminReportsController controller() {
        return new AdminReportsController(reportRepo, userRepo, announcementRepo, auditService, reportService,
                userService, announcementService, notificationDispatcher);
    }

    private static Authentication authAs(UUID adminId, List<AdminPermission> extraAuthorities) {
        List<GrantedAuthority> authorities = extraAuthorities.stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .toList();
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin@yadony.com", AdminRole.ADMIN, false, "uid-admin");
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    // ---- listReports ----

    @Test
    void listReports_noFilter_returnsEmptyPage() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listReports_withStatusFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(ReportStatus.OPEN, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportRepo).findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class));
    }

    @Test
    void listReports_withTargetTypeFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(isNull(), eq(ReportTargetType.USER), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, ReportTargetType.USER, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportRepo).findFiltered(isNull(), eq(ReportTargetType.USER), any(Pageable.class));
    }

    @Test
    void listReports_enrichesReporterName() {
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);

        UserEntity reporter = new UserEntity();
        reporter.setFirstName("Jean");
        reporter.setLastName("Dupont");

        ReflectionTestUtils.setField(reporter, "id", reporterId);
        Page<ReportEntity> page = new PageImpl<>(List.of(report));
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(reporter));

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, null, null, 0, 20);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getContent()).hasSize(1);
        assertThat(resp.getBody().getContent().get(0).reporterName()).isEqualTo("Jean Dupont");
    }

    @Test
    void listReports_exposeLaRouteDeLEcran_pourUnRapportScarabee() {
        ReportEntity report = new ReportEntity();
        report.setTargetType(ReportTargetType.APP);
        report.setReporterId(UUID.randomUUID());
        report.setReason(ReportReason.SCREEN_BUG);
        report.setDescription("Le badge passe sous le bouton");
        report.setScreenRoute("/profile");
        report.setStatus(ReportStatus.OPEN);
        Page<ReportEntity> page = new PageImpl<>(List.of(report));
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of());

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, null, null, 0, 20);

        AdminReportResponse first = resp.getBody().getContent().get(0);
        assertThat(first.reason()).isEqualTo("SCREEN_BUG");
        assertThat(first.screenRoute()).isEqualTo("/profile");
        assertThat(first.targetLabel()).isNull();
    }

    @Test
    void listReports_enrichesTargetLabel_forUserTarget() {
        UUID reporterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.USER);
        report.setTargetId(targetId);

        UserEntity target = new UserEntity();
        target.setFirstName("Awa");
        target.setLastName("Ndiaye");
        ReflectionTestUtils.setField(target, "id", targetId);

        Page<ReportEntity> page = new PageImpl<>(List.of(report));
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(target));

        ResponseEntity<Page<AdminReportResponse>> resp = controller().listReports(null, null, null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).targetLabel()).isEqualTo("Awa Ndiaye");
    }

    @Test
    void listReports_enrichesTargetLabel_forAnnouncementTarget() {
        UUID reporterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.ANNOUNCEMENT);
        report.setTargetId(targetId);

        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", targetId);
        ann.setDepartureCity("Lyon");
        ann.setArrivalCity("Abidjan");

        Page<ReportEntity> page = new PageImpl<>(List.of(report));
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of());
        when(announcementRepo.findAllById(anyCollection())).thenReturn(List.of(ann));

        ResponseEntity<Page<AdminReportResponse>> resp = controller().listReports(null, null, null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).targetLabel()).contains("Lyon").contains("Abidjan");
    }

    // ---- resolveReport ----

    @Test
    void resolveReport_dismiss_setsDismissedStatus_noSideEffect() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.DISMISS, "Non fondé");
        ResponseEntity<AdminReportResponse> resp =
                controller().resolveReport(id, request, authAs(adminId, List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(report.getActionTaken()).isEqualTo(ReportAction.DISMISS);
        verifyNoInteractions(userService, announcementService, notificationDispatcher);
    }

    @Test
    void resolveReport_warn_notifiesTargetUser_setsResolvedStatus() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.USER);
        report.setTargetId(targetId);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.WARN, "Comportement signalé");
        controller().resolveReport(id, request, authAs(adminId, List.of()));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        verify(notificationDispatcher).notifyUser(eq(targetId), anyString(), anyString(), anyMap());
    }

    @Test
    void resolveReport_warn_onNonUserTarget_throws422() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.ANNOUNCEMENT);
        report.setTargetId(UUID.randomUUID());
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.WARN, "note");
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, request, authAs(UUID.randomUUID(), List.of())));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void resolveReport_suspendTarget_delegatesToUserService_withAdminId() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.USER);
        report.setTargetId(targetId);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.SUSPEND_TARGET, "Récidiviste");
        controller().resolveReport(id, request, authAs(adminId, List.of(AdminPermission.USER_SUSPEND)));

        verify(userService).suspendUser(targetId, "Récidiviste", adminId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    void resolveReport_suspendTarget_withoutPermission_throws403() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.USER);
        report.setTargetId(UUID.randomUUID());
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.SUSPEND_TARGET, "note");
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, request, authAs(UUID.randomUUID(), List.of())));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(userService);
    }

    @Test
    void resolveReport_suspendTarget_onNonUserTarget_throws422() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.ANNOUNCEMENT);
        report.setTargetId(UUID.randomUUID());
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.SUSPEND_TARGET, "note");
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, request,
                        authAs(UUID.randomUUID(), List.of(AdminPermission.USER_SUSPEND))));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void resolveReport_removeContent_delegatesToAnnouncementService_withAdminId() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.ANNOUNCEMENT);
        report.setTargetId(targetId);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.REMOVE_CONTENT, "Objet interdit");
        controller().resolveReport(id, request, authAs(adminId, List.of(AdminPermission.CONTENT_REMOVE)));

        verify(announcementService).removeByAdmin(eq(targetId), eq(adminId), any(), eq("Objet interdit"));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    void resolveReport_removeContent_withoutPermission_throws403() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.ANNOUNCEMENT);
        report.setTargetId(UUID.randomUUID());
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.REMOVE_CONTENT, "note");
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, request, authAs(UUID.randomUUID(), List.of())));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(announcementService);
    }

    @Test
    void resolveReport_actionRequired_throws400() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        ResolveReportRequest request = new ResolveReportRequest(null, "note");
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, request, authAs(UUID.randomUUID(), List.of())));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolveReport_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(reportRepo.findById(id)).thenReturn(Optional.empty());

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.DISMISS, "note");
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, request, authAs(UUID.randomUUID(), List.of())));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolveReport_auditsWithRealAdminId_notNull() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.DISMISS, "note");
        controller().resolveReport(id, request, authAs(adminId, List.of()));

        verify(auditService).log(eq("REPORT"), eq(id), eq("REPORT_RESOLVED"), eq(adminId), anyMap());
    }

    @Test
    void resolveReport_responseContainsStatusAndAction() {
        UUID id = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest(ReportAction.DISMISS, "Récidiviste");
        ResponseEntity<AdminReportResponse> resp =
                controller().resolveReport(id, request, authAs(UUID.randomUUID(), List.of()));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("DISMISSED");
        assertThat(resp.getBody().actionTaken()).isEqualTo("DISMISS");
        assertThat(resp.getBody().resolutionNote()).isEqualTo("Récidiviste");
    }

    // ---- recherche q ----

    @Test
    void listReports_withQuery_usesSearchWithNormalizedNeedleAndMatchingReasons() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.searchFiltered(isNull(), isNull(), eq("%badge%"), eq(false), eq(List.of()),
                any(Pageable.class))).thenReturn(page);

        controller().listReports(null, null, "  Badge ", 0, 20);

        verify(reportRepo).searchFiltered(isNull(), isNull(), eq("%badge%"), eq(false), eq(List.of()),
                any(Pageable.class));
        verify(reportRepo, never()).findFiltered(any(), any(), any(Pageable.class));
    }

    @Test
    void listReports_withQueryMatchingAReasonLabel_passesTheReasons() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.searchFiltered(isNull(), isNull(), eq("%écran%"), eq(true),
                eq(List.of(ReportReason.SCREEN_BUG)), any(Pageable.class))).thenReturn(page);

        controller().listReports(null, null, "écran", 0, 20);

        verify(reportRepo).searchFiltered(isNull(), isNull(), eq("%écran%"), eq(true),
                eq(List.of(ReportReason.SCREEN_BUG)), any(Pageable.class));
    }

    @Test
    void listReports_blankQuery_fallsBackToPlainFilter() {
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller().listReports(null, null, "   ", 0, 20);

        verify(reportRepo).findFiltered(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void normalizeQuery_andReasonsMatching() {
        assertThat(AdminReportsController.normalizeQuery(null)).isNull();
        assertThat(AdminReportsController.normalizeQuery("  ")).isNull();
        assertThat(AdminReportsController.normalizeQuery(" Spam ")).isEqualTo("%spam%");
        assertThat(AdminReportsController.reasonsMatching("%spam%")).containsExactly(ReportReason.SPAM);
        assertThat(AdminReportsController.reasonsMatching("%bug%"))
                .containsExactlyInAnyOrder(ReportReason.APP_BUG, ReportReason.SCREEN_BUG);
        assertThat(AdminReportsController.reasonsMatching("%zzz%")).isEmpty();
    }

    // ---- suppression ----

    @Test
    void deleteReport_softDeletesAndAudits() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        ReflectionTestUtils.setField(report, "id", id);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        ResponseEntity<Void> resp = controller().deleteReport(id, authAs(adminId, List.of()));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(report.getDeletedAt()).isNotNull();
        verify(reportRepo).save(report);
        verify(auditService).log(eq("REPORT"), eq(id), eq("REPORT_DELETED"), eq(adminId), any());
    }

    @Test
    void deleteReport_unknown_throws404() {
        UUID id = UUID.randomUUID();
        when(reportRepo.findById(id)).thenReturn(Optional.empty());

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().deleteReport(id, authAs(UUID.randomUUID(), List.of())));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void bulkDelete_byIds_softDeletesEachOnce_andSkipsAlreadyDeleted() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ReportEntity ra = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        ReportEntity rb = buildReport(UUID.randomUUID(), ReportStatus.RESOLVED);
        ReflectionTestUtils.setField(ra, "id", a);
        ReflectionTestUtils.setField(rb, "id", b);
        rb.softDelete();
        when(reportRepo.findAllById(List.of(a, b))).thenReturn(List.of(ra, rb));

        ResponseEntity<Map<String, Integer>> resp = controller().bulkDeleteReports(
                new AdminReportsController.BulkDeleteReportsRequest(List.of(a, b), false, null, null, null),
                authAs(UUID.randomUUID(), List.of()));

        assertThat(resp.getBody()).containsEntry("deleted", 1);
        assertThat(ra.getDeletedAt()).isNotNull();
        verify(reportRepo, times(1)).save(any());
        verify(auditService, times(1)).log(eq("REPORT"), eq(a), eq("REPORT_DELETED"), any(), any());
    }

    @Test
    void bulkDelete_all_resolvesIdsFromTheCurrentFilter() {
        UUID a = UUID.randomUUID();
        ReportEntity ra = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        ReflectionTestUtils.setField(ra, "id", a);
        when(reportRepo.findFilteredIds(eq(ReportStatus.OPEN), eq(ReportTargetType.APP), eq("%bug%"),
                eq(true), eq(List.of(ReportReason.APP_BUG, ReportReason.SCREEN_BUG)))).thenReturn(List.of(a));
        when(reportRepo.findAllById(List.of(a))).thenReturn(List.of(ra));

        ResponseEntity<Map<String, Integer>> resp = controller().bulkDeleteReports(
                new AdminReportsController.BulkDeleteReportsRequest(null, true, ReportStatus.OPEN,
                        ReportTargetType.APP, "Bug"),
                authAs(UUID.randomUUID(), List.of()));

        assertThat(resp.getBody()).containsEntry("deleted", 1);
        assertThat(ra.getDeletedAt()).isNotNull();
    }

    @Test
    void bulkDelete_emptySelection_deletesNothing() {
        ResponseEntity<Map<String, Integer>> resp = controller().bulkDeleteReports(
                new AdminReportsController.BulkDeleteReportsRequest(List.of(), false, null, null, null),
                authAs(UUID.randomUUID(), List.of()));

        assertThat(resp.getBody()).containsEntry("deleted", 0);
        verify(reportRepo, never()).findAllById(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // ---- helpers ----

    private ReportEntity buildReport(UUID reporterId, ReportStatus status) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.USER);
        r.setTargetId(UUID.randomUUID());
        r.setReporterId(reporterId);
        r.setReason(ReportReason.HARASSMENT);
        r.setDescription("Envoi de spam répété");
        r.setStatus(status);
        return r;
    }
}

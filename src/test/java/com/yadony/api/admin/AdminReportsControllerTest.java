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
    @Mock com.yadony.api.requests.repository.PackageRequestRepository packageRequestRepo;
    @Mock com.yadony.api.requests.service.PackageRequestModerationService packageRequestModerationService;
    @Mock DeletionTraceService deletionTraceService;
    @Mock com.yadony.api.matching.BidRepository bidRepo;
    @Mock com.yadony.api.ratings.RatingRepository ratingRepo;
    @Mock com.yadony.api.messaging.ConversationRepository conversationRepo;
    @Mock com.yadony.api.messaging.FirestoreService firestoreService;
    @Mock AdminMessageModerationService messageModeration;
    @Mock AdminRatingModerationService ratingModeration;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void stubMessages() {
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private AdminReportsController controller() {
        ReportTargetResolver resolver = new ReportTargetResolver(userRepo, announcementRepo, packageRequestRepo,
                bidRepo, ratingRepo, conversationRepo, firestoreService);
        ReportActionExecutor executor = new ReportActionExecutor(userService, announcementService,
                packageRequestModerationService, notificationDispatcher, messageModeration, ratingModeration);
        return new AdminReportsController(reportRepo, userRepo, announcementRepo, auditService, reportService,
                packageRequestRepo, deletionTraceService, resolver, executor, eventPublisher);
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
                controller().listReports(null, null, null, false, 0, 20, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listReports_withStatusFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(ReportStatus.OPEN, null, null, false, 0, 20, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportRepo).findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class));
    }

    @Test
    void listReports_withTargetTypeFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(isNull(), eq(ReportTargetType.USER), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, ReportTargetType.USER, null, false, 0, 20, null);

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
                controller().listReports(null, null, null, false, 0, 20, null);

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
                controller().listReports(null, null, null, false, 0, 20, null);

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

        ResponseEntity<Page<AdminReportResponse>> resp = controller().listReports(null, null, null, false, 0, 20, null);

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

        ResponseEntity<Page<AdminReportResponse>> resp = controller().listReports(null, null, null, false, 0, 20, null);

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

        controller().listReports(null, null, "  Badge ", false, 0, 20, null);

        verify(reportRepo).searchFiltered(isNull(), isNull(), eq("%badge%"), eq(false), eq(List.of()),
                any(Pageable.class));
        verify(reportRepo, never()).findFiltered(any(), any(), any(Pageable.class));
    }

    @Test
    void listReports_withQueryMatchingAReasonLabel_passesTheReasons() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.searchFiltered(isNull(), isNull(), eq("%écran%"), eq(true),
                eq(List.of(ReportReason.SCREEN_BUG)), any(Pageable.class))).thenReturn(page);

        controller().listReports(null, null, "écran", false, 0, 20, null);

        verify(reportRepo).searchFiltered(isNull(), isNull(), eq("%écran%"), eq(true),
                eq(List.of(ReportReason.SCREEN_BUG)), any(Pageable.class));
    }

    @Test
    void listReports_blankQuery_fallsBackToPlainFilter() {
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller().listReports(null, null, "   ", false, 0, 20, null);

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

    // ---- corbeille et restauration ----

    @Test
    void listReports_deleted_readsTrashWithDeletingAdmin() {
        UUID id = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ReportEntity r = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        ReflectionTestUtils.setField(r, "id", id);
        r.softDelete();
        when(reportRepo.findDeletedFiltered(eq("OPEN"), eq("USER"), eq("%spam%"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(r)));
        when(userRepo.findAllById(any())).thenReturn(List.of());
        when(deletionTraceService.latest(eq("REPORT"), eq("REPORT_DELETED"), any()))
                .thenReturn(Map.of(id, new DeletionTraceService.DeletionTrace(admin, "admin@yadony.test",
                        "HARASSMENT", r.getDeletedAt())));

        var resp = controller().listReports(ReportStatus.OPEN, ReportTargetType.USER, " Spam ", true, 0, 20, null);

        AdminReportResponse item = resp.getBody().getContent().get(0);
        assertThat(item.deletedAt()).isEqualTo(r.getDeletedAt());
        assertThat(item.deletedByAdminEmail()).isEqualTo("admin@yadony.test");
        verify(reportRepo, never()).findFiltered(any(), any(), any());
        verify(reportRepo, never()).searchFiltered(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void listReports_active_hasNoDeletionFields() {
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(buildReport(UUID.randomUUID(), ReportStatus.OPEN))));
        when(userRepo.findAllById(any())).thenReturn(List.of());

        var item = controller().listReports(null, null, null, false, 0, 20, null).getBody().getContent().get(0);

        assertThat(item.deletedAt()).isNull();
        assertThat(item.deletedByAdminEmail()).isNull();
        verifyNoInteractions(deletionTraceService);
    }

    private ReportEntity deletedReport(UUID id) {
        ReportEntity r = buildReport(UUID.randomUUID(), ReportStatus.RESOLVED);
        ReflectionTestUtils.setField(r, "id", id);
        r.softDelete();
        return r;
    }

    @Test
    void restoreReport_clearsDeletedAt_andAuditsAdminWithReason() {
        UUID id = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ReportEntity r = deletedReport(id);
        when(reportRepo.findAllByIdIncludingDeleted(List.of(id))).thenReturn(List.of(r));
        when(userRepo.findAllById(any())).thenReturn(List.of());

        var resp = controller().restoreReport(id,
                new com.yadony.api.admin.dto.RestoreRequest("  Supprime par erreur  "), authAs(admin, List.of()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getDeletedAt()).isNull();
        assertThat(resp.getBody().deletedAt()).isNull();
        verify(reportRepo).save(r);
        verify(auditService).log(eq("REPORT"), eq(id), eq("REPORT_RESTORED"), eq(admin),
                eq(Map.of("reportId", id.toString(), "mode", "single", "reason", "Supprime par erreur")));
    }

    @Test
    void restoreReport_withoutBody_auditsEmptyReason() {
        UUID id = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(reportRepo.findAllByIdIncludingDeleted(List.of(id))).thenReturn(List.of(deletedReport(id)));
        when(userRepo.findAllById(any())).thenReturn(List.of());

        controller().restoreReport(id, null, authAs(admin, List.of()));

        verify(auditService).log(eq("REPORT"), eq(id), eq("REPORT_RESTORED"), eq(admin),
                eq(Map.of("reportId", id.toString(), "mode", "single", "reason", "")));
    }

    @Test
    void restoreReport_notDeleted_throws409() {
        UUID id = UUID.randomUUID();
        ReportEntity r = deletedReport(id);
        r.setDeletedAt(null);
        when(reportRepo.findAllByIdIncludingDeleted(List.of(id))).thenReturn(List.of(r));

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().restoreReport(id, null, authAs(UUID.randomUUID(), List.of())));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("report-not-deleted");
        verify(reportRepo, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void restoreReport_unknown_throws404() {
        UUID id = UUID.randomUUID();
        when(reportRepo.findAllByIdIncludingDeleted(List.of(id))).thenReturn(List.of());

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().restoreReport(id, null, authAs(UUID.randomUUID(), List.of())));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("report-not-found");
    }

    @Test
    void bulkRestore_restoresDeleted_andCountsVisibleOrUnknownAsSkipped() {
        UUID deleted = UUID.randomUUID();
        UUID visible = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ReportEntity rd = deletedReport(deleted);
        ReportEntity rv = deletedReport(visible);
        rv.setDeletedAt(null);
        when(reportRepo.findAllByIdIncludingDeleted(any())).thenReturn(List.of(rd, rv));

        var resp = controller().bulkRestoreReports(
                new AdminReportsController.BulkRestoreReportsRequest(List.of(deleted, visible, unknown, deleted)),
                authAs(admin, List.of()));

        assertThat(resp.getBody()).containsEntry("restored", 1).containsEntry("skipped", 2);
        assertThat(rd.getDeletedAt()).isNull();
        verify(reportRepo, times(1)).save(rd);
        verify(auditService).log(eq("REPORT"), eq(deleted), eq("REPORT_RESTORED"), eq(admin),
                eq(Map.of("reportId", deleted.toString(), "mode", "bulk", "reason", "")));
        verify(auditService, times(1)).log(any(), any(), any(), any(), any());
    }

    @Test
    void bulkRestore_empty_restoresNothing() {
        var resp = controller().bulkRestoreReports(
                new AdminReportsController.BulkRestoreReportsRequest(List.of()), authAs(UUID.randomUUID(), List.of()));

        assertThat(resp.getBody()).containsEntry("restored", 0).containsEntry("skipped", 0);
        verify(reportRepo, never()).findAllByIdIncludingDeleted(any());
    }

    @Test
    void bulkRestore_moreThan100Ids_is422_beforeAnyRead() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(101).toList();

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().bulkRestoreReports(new AdminReportsController.BulkRestoreReportsRequest(ids),
                        authAs(UUID.randomUUID(), List.of())));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getErrorCode()).isEqualTo("bulk-restore-too-many");
        verifyNoInteractions(auditService);
        verify(reportRepo, never()).findAllByIdIncludingDeleted(any());
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

    // ---- Cible PACKAGE_REQUEST ----

    @Test
    void listReports_ciblePackageRequest_libelleDuCorridor() {
        UUID requestId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.PACKAGE_REQUEST);
        report.setTargetId(requestId);
        com.yadony.api.requests.entity.PackageRequestEntity pr = new com.yadony.api.requests.entity.PackageRequestEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(pr, "id", requestId);
        pr.setDepartureCity("Paris");
        pr.setArrivalCity("Dakar");
        when(reportRepo.findFiltered(isNull(), eq(ReportTargetType.PACKAGE_REQUEST), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(report)));
        when(packageRequestRepo.findAllById(any())).thenReturn(List.of(pr));

        var page = controller().listReports(null, ReportTargetType.PACKAGE_REQUEST, null, false, 0, 20, null).getBody();

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).targetType()).isEqualTo("PACKAGE_REQUEST");
        assertThat(page.getContent().get(0).targetLabel())
                .isEqualTo(com.yadony.api.common.MatchingTextUtil.corridorLabel("Paris", "Dakar"));
    }

    @Test
    void resolveReport_removeContent_ciblePackageRequest_passeParLaModerationDesDemandes() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.PACKAGE_REQUEST);
        report.setTargetId(targetId);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        controller().resolveReport(id, new ResolveReportRequest(ReportAction.REMOVE_CONTENT, "fraude"),
                authAs(adminId, List.of(AdminPermission.CONTENT_REMOVE)));

        verify(packageRequestModerationService).removeByAdmin(eq(targetId), eq(adminId),
                eq(com.yadony.api.matching.AnnouncementRemovalReason.OTHER), eq("fraude"));
        verifyNoInteractions(announcementService);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    // ---- actions adaptées à la cible, auteur, notification du signalant ----

    private static final List<AdminPermission> TOUTES = List.of(AdminPermission.values());

    private static UserEntity userWithId(UUID id, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private com.yadony.api.ratings.RatingEntity ratingBy(UUID ratingId, UUID raterId) {
        var rating = new com.yadony.api.ratings.RatingEntity();
        ReflectionTestUtils.setField(rating, "id", ratingId);
        rating.setRaterId(raterId);
        return rating;
    }

    @Test
    void listReports_signalementOuvert_actionsDisponiblesEtAuteur() {
        UUID ratingId = UUID.randomUUID();
        UUID raterId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.RATING);
        report.setTargetId(ratingId);
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(report)));
        when(ratingRepo.findAllById(anyCollection())).thenReturn(List.of(ratingBy(ratingId, raterId)));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(userWithId(raterId, "Awa", "Diallo")));

        var item = controller().listReports(null, null, null, false, 0, 20, authAs(UUID.randomUUID(), TOUTES))
                .getBody().getContent().get(0);

        assertThat(item.availableActions()).containsExactly("RESOLVE", "DISMISS", "WARN_AUTHOR", "SUSPEND_AUTHOR",
                "EXCLUDE_RATING", "DELETE_RATING");
        assertThat(item.targetAuthor()).isEqualTo(new AdminReportResponse.TargetAuthor(raterId, "Awa D."));
    }

    @Test
    void listReports_supportSansPermissionsDeModeration_actionsRestreintes() {
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.APP);
        report.setTargetId(null);
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(report)));

        var item = controller().listReports(null, null, null, false, 0, 20,
                authAs(UUID.randomUUID(), List.of(AdminPermission.REPORT_VIEW, AdminPermission.REPORT_RESOLVE)))
                .getBody().getContent().get(0);

        assertThat(item.availableActions()).containsExactly("RESOLVE", "DISMISS");
        assertThat(item.targetAuthor()).isNull();
    }

    @Test
    void listReports_signalementDejaTraite_aucuneAction() {
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.RESOLVED);
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(report)));

        var item = controller().listReports(null, null, null, false, 0, 20, authAs(UUID.randomUUID(), TOUTES))
                .getBody().getContent().get(0);

        assertThat(item.availableActions()).isEmpty();
    }

    @Test
    void getReport_detailAvecActions() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.APP);
        report.setTargetId(null);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        var body = controller().getReport(id, authAs(UUID.randomUUID(), TOUTES)).getBody();

        assertThat(body.availableActions()).containsExactly("RESOLVE", "DISMISS");
    }

    @Test
    void getReport_introuvable_404() {
        UUID id = UUID.randomUUID();
        when(reportRepo.findById(id)).thenReturn(Optional.empty());

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().getReport(id, authAs(UUID.randomUUID(), TOUTES)));
        assertThat(ex.getErrorCode()).isEqualTo("report-not-found");
    }

    @Test
    void resolve_rapportScarabee_marqueTraiteEtRemercieLeSignalant() {
        UUID id = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.APP);
        report.setTargetId(null);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(userWithId(reporterId, "Jean", "Dupont")));

        var resp = controller().resolveReport(id, new ResolveReportRequest(ReportAction.RESOLVE, "Corrigé en 1.0.84"),
                authAs(UUID.randomUUID(), List.of()));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getActionTaken()).isEqualTo(ReportAction.RESOLVE);
        assertThat(resp.getBody().availableActions()).isEmpty();
        verify(eventPublisher).publishEvent(new com.yadony.api.signalements.events.ReportResolvedEvent(id, reporterId));
        verifyNoInteractions(userService, announcementService, notificationDispatcher);
    }

    @Test
    void resolve_rejet_neNotifiePasLeSignalant() {
        UUID id = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        lenient().when(userRepo.findAllById(anyCollection())).thenReturn(List.of(userWithId(reporterId, "Jean", "D")));

        controller().resolveReport(id, new ResolveReportRequest(ReportAction.DISMISS, "non fondé"),
                authAs(UUID.randomUUID(), List.of()));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resolve_signalantSupprimeOuAbsent_pasDeNotification() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.APP);
        report.setTargetId(null);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        // compte supprimé : non relu (@Where deleted_at IS NULL)
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of());

        controller().resolveReport(id, new ResolveReportRequest(ReportAction.RESOLVE, null),
                authAs(UUID.randomUUID(), List.of()));

        UUID id2 = UUID.randomUUID();
        ReportEntity anonyme = buildReport(null, ReportStatus.OPEN);
        anonyme.setTargetType(ReportTargetType.APP);
        anonyme.setTargetId(null);
        when(reportRepo.findById(id2)).thenReturn(Optional.of(anonyme));
        controller().resolveReport(id2, new ResolveReportRequest(ReportAction.RESOLVE, null),
                authAs(UUID.randomUUID(), List.of()));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resolve_dejaTraite_409() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.RESOLVED);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, new ResolveReportRequest(ReportAction.RESOLVE, null),
                        authAs(UUID.randomUUID(), TOUTES)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("report-already-closed");
        verifyNoInteractions(eventPublisher, auditService);
    }

    @Test
    void resolve_deleteMessage_messageIntrouvable_422() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.MESSAGE);
        report.setTargetId(UUID.randomUUID());
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, new ResolveReportRequest(ReportAction.DELETE_MESSAGE, "x"),
                        authAs(UUID.randomUUID(), TOUTES)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getErrorCode()).isEqualTo("report-target-unresolvable");
        verifyNoInteractions(messageModeration, eventPublisher);
    }

    @Test
    void resolve_deleteRating_sansPermission_403AvantToutGeste() {
        UUID id = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.RATING);
        report.setTargetId(UUID.randomUUID());
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveReport(id, new ResolveReportRequest(ReportAction.DELETE_RATING, "x"),
                        authAs(UUID.randomUUID(), List.of(AdminPermission.RATING_MODERATE))));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(ratingModeration, ratingRepo);
    }

    @Test
    void resolve_deleteRating_delegueEtAuditeLaResolution() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID ratingId = UUID.randomUUID();
        ReportEntity report = buildReport(UUID.randomUUID(), ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.RATING);
        report.setTargetId(ratingId);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(ratingRepo.findAllById(anyCollection())).thenReturn(List.of(ratingBy(ratingId, null)));

        controller().resolveReport(id, new ResolveReportRequest(ReportAction.DELETE_RATING, "insultes"),
                authAs(adminId, List.of(AdminPermission.RATING_DELETE)));

        verify(ratingModeration).delete(ratingId, "insultes", adminId);
        verify(auditService).log("REPORT", id, "REPORT_RESOLVED", adminId,
                Map.of("reportId", id.toString(), "action", "DELETE_RATING", "note", "insultes"));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    void resolve_suspendAuthor_offre_suspendLaPartieAdverseEtAuditeLAuteur() {
        UUID id = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID annId = UUID.randomUUID();
        ReportEntity report = buildReport(sender, ReportStatus.OPEN);
        report.setTargetType(ReportTargetType.BID);
        report.setTargetId(bidId);
        var bid = new com.yadony.api.matching.BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(sender);
        bid.setAnnouncementId(annId);
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", annId);
        ann.setTravelerId(traveler);
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(bidRepo.findAllById(anyCollection())).thenReturn(List.of(bid));
        when(announcementRepo.findAllById(anyCollection())).thenReturn(List.of(ann));
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(
                userWithId(traveler, "Ibrahim", "Koné"), userWithId(sender, "Fatou", "Sow")));

        var resp = controller().resolveReport(id, new ResolveReportRequest(ReportAction.SUSPEND_AUTHOR, "arnaque"),
                authAs(adminId, List.of(AdminPermission.USER_SUSPEND)));

        verify(userService).suspendUser(traveler, "arnaque", adminId);
        verify(auditService).log("REPORT", id, "REPORT_RESOLVED", adminId,
                Map.of("reportId", id.toString(), "action", "SUSPEND_AUTHOR", "note", "arnaque",
                        "authorId", traveler.toString()));
        assertThat(resp.getBody().targetAuthor()).isEqualTo(new AdminReportResponse.TargetAuthor(traveler, "Ibrahim K."));
        verify(eventPublisher).publishEvent(new com.yadony.api.signalements.events.ReportResolvedEvent(id, sender));
    }
}

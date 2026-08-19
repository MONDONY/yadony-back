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
                controller().listReports(null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listReports_withStatusFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(ReportStatus.OPEN, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportRepo).findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class));
    }

    @Test
    void listReports_withTargetTypeFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(isNull(), eq(ReportTargetType.USER), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, ReportTargetType.USER, 0, 20);

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
                controller().listReports(null, null, 0, 20);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getContent()).hasSize(1);
        assertThat(resp.getBody().getContent().get(0).reporterName()).isEqualTo("Jean Dupont");
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

        ResponseEntity<Page<AdminReportResponse>> resp = controller().listReports(null, null, 0, 20);

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

        ResponseEntity<Page<AdminReportResponse>> resp = controller().listReports(null, null, 0, 20);

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

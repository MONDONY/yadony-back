package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminReportResponse;
import com.yadony.api.admin.dto.ResolveReportRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRemovalReason;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.signalements.ReportAction;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportService;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportsController {

    private final ReportRepository reportRepo;
    private final UserRepository userRepo;
    private final AnnouncementRepository announcementRepo;
    private final AuditService auditService;
    private final ReportService reportService;
    private final UserService userService;
    private final AnnouncementService announcementService;
    private final NotificationDispatcher notificationDispatcher;

    public AdminReportsController(ReportRepository reportRepo,
                                  UserRepository userRepo,
                                  AnnouncementRepository announcementRepo,
                                  AuditService auditService,
                                  ReportService reportService,
                                  UserService userService,
                                  AnnouncementService announcementService,
                                  NotificationDispatcher notificationDispatcher) {
        this.reportRepo = reportRepo;
        this.userRepo = userRepo;
        this.announcementRepo = announcementRepo;
        this.auditService = auditService;
        this.reportService = reportService;
        this.userService = userService;
        this.announcementService = announcementService;
        this.notificationDispatcher = notificationDispatcher;
    }

    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @GetMapping("/admin/reports")
    public ResponseEntity<Page<AdminReportResponse>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ReportEntity> reports = reportRepo.findFiltered(
                status, targetType,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        // Une même requête couvre signalants ET cibles de type USER : les deux se lisent
        // dans la même table, inutile de doubler l'aller-retour.
        Set<UUID> userIds = reports.getContent().stream()
                .flatMap(r -> java.util.stream.Stream.of(
                        r.getReporterId(),
                        r.getTargetType() == ReportTargetType.USER ? r.getTargetId() : null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserEntity> usersById = userRepo.findAllById(userIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        Set<UUID> announcementIds = reports.getContent().stream()
                .filter(r -> r.getTargetType() == ReportTargetType.ANNOUNCEMENT)
                .map(ReportEntity::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, AnnouncementEntity> announcementsById = announcementRepo.findAllById(announcementIds).stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity(), (a, b) -> a));

        Map<UUID, List<String>> photosByReport = reportService.photoUrlsByReport(
                reports.getContent().stream().map(ReportEntity::getId).collect(Collectors.toSet()));

        Page<AdminReportResponse> result = reports.map(r ->
                toResponse(r, usersById, announcementsById, photosByReport.getOrDefault(r.getId(), List.of())));
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAuthority('REPORT_RESOLVE')")
    @PostMapping("/admin/reports/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminReportResponse> resolveReport(
            @PathVariable UUID id,
            @RequestBody ResolveReportRequest request,
            Authentication authentication) {

        ReportEntity report = reportRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));

        ReportAction action = request.action();
        if (action == null) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "action-required",
                    "Invalid Request", "L'action est obligatoire");
        }
        if (!action.appliesTo(report.getTargetType())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "action-not-applicable",
                    "Unprocessable", "Cette action ne s'applique pas à ce type de cible");
        }

        UUID adminId = adminId(authentication);
        applyAction(id, action, report, request.note(), authentication, adminId);

        report.setStatus(action == ReportAction.DISMISS ? ReportStatus.DISMISSED : ReportStatus.RESOLVED);
        report.setActionTaken(action);
        report.setResolutionNote(request.note());
        report.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reportRepo.save(report);

        auditService.log("REPORT", id, "REPORT_RESOLVED", adminId,
                Map.of(
                        "reportId", id.toString(),
                        "action", action.name(),
                        "note", request.note() != null ? request.note() : ""
                ));

        Map<UUID, UserEntity> singleUser = userRepo.findAllById(
                report.getReporterId() != null ? Set.of(report.getReporterId()) : Set.of()).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toResponse(report, singleUser, Map.of(), reportService.photoUrls(report.getId())));
    }

    /**
     * SUSPEND_TARGET et REMOVE_CONTENT délèguent aux services de modération déjà existants
     * (UserService, AnnouncementService) plutôt que de réimplémenter la logique — et exigent
     * la permission spécifique de ce geste au-delà de REPORT_RESOLVE : un support qui traite
     * des signalements ne doit pas pouvoir retirer du contenu (CONTENT_REMOVE) par ce détour.
     */
    private void applyAction(UUID reportId, ReportAction action, ReportEntity report, String note,
                             Authentication authentication, UUID adminId) {
        switch (action) {
            case DISMISS -> { }
            case WARN -> notificationDispatcher.notifyUser(report.getTargetId(),
                    "Avertissement Yadony",
                    note != null && !note.isBlank()
                            ? note
                            : "Un comportement signalé sur votre compte a été examiné par notre équipe.",
                    Map.of("type", "ADMIN_WARNING", "reportId", reportId.toString()));
            case SUSPEND_TARGET -> {
                requireAuthority(authentication, AdminPermission.USER_SUSPEND.name());
                userService.suspendUser(report.getTargetId(), note, adminId);
            }
            case REMOVE_CONTENT -> {
                requireAuthority(authentication, AdminPermission.CONTENT_REMOVE.name());
                announcementService.removeByAdmin(report.getTargetId(), adminId,
                        AnnouncementRemovalReason.OTHER, note);
            }
        }
    }

    private void requireAuthority(Authentication authentication, String authority) {
        boolean has = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
        if (!has) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "authority-required",
                    "Forbidden", "Permission " + authority + " requise pour cette action");
        }
    }

    private UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AdminReportResponse toResponse(ReportEntity r, Map<UUID, UserEntity> users,
                                           Map<UUID, AnnouncementEntity> announcements, List<String> photoUrls) {
        String reporterName = resolveReporterName(r.getReporterId(), users);
        return new AdminReportResponse(
                r.getId(),
                r.getTargetType() != null ? r.getTargetType().name() : null,
                r.getTargetId(),
                resolveTargetLabel(r, users, announcements),
                reporterName,
                r.getReason() != null ? r.getReason().name() : null,
                r.getDescription(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getActionTaken() != null ? r.getActionTaken().name() : null,
                r.getResolutionNote(),
                r.getResolvedAt(),
                r.getCreatedAt(),
                photoUrls
        );
    }

    private String resolveReporterName(UUID reporterId, Map<UUID, UserEntity> users) {
        if (reporterId == null) return null;
        UserEntity u = users.get(reporterId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }

    private String resolveTargetLabel(ReportEntity r, Map<UUID, UserEntity> users,
                                      Map<UUID, AnnouncementEntity> announcements) {
        if (r.getTargetId() == null || r.getTargetType() == null) return null;
        return switch (r.getTargetType()) {
            case USER -> {
                UserEntity u = users.get(r.getTargetId());
                yield u != null ? MatchingTextUtil.buildName(u) : null;
            }
            case ANNOUNCEMENT -> {
                AnnouncementEntity a = announcements.get(r.getTargetId());
                yield a != null ? MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity()) : null;
            }
            case BID, MESSAGE, RATING, APP -> null;
        };
    }
}

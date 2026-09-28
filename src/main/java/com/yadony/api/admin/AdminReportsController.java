package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminReportResponse;
import com.yadony.api.admin.dto.ResolveReportRequest;
import com.yadony.api.admin.dto.RestoreRequest;
import jakarta.validation.Valid;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.signalements.ReportAction;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportService;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.signalements.events.ReportResolvedEvent;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final PackageRequestRepository packageRequestRepo;
    private final DeletionTraceService deletionTraceService;
    private final ReportTargetResolver targetResolver;
    private final ReportActionExecutor actionExecutor;
    private final ApplicationEventPublisher eventPublisher;

    /** Borne d'une restauration groupée : une page d'écran, pas une restauration de masse. */
    static final int MAX_BULK_RESTORE = 100;

    public AdminReportsController(ReportRepository reportRepo,
                                  UserRepository userRepo,
                                  AnnouncementRepository announcementRepo,
                                  AuditService auditService,
                                  ReportService reportService,
                                  PackageRequestRepository packageRequestRepo,
                                  DeletionTraceService deletionTraceService,
                                  ReportTargetResolver targetResolver,
                                  ReportActionExecutor actionExecutor,
                                  ApplicationEventPublisher eventPublisher) {
        this.reportRepo = reportRepo;
        this.userRepo = userRepo;
        this.announcementRepo = announcementRepo;
        this.auditService = auditService;
        this.reportService = reportService;
        this.packageRequestRepo = packageRequestRepo;
        this.deletionTraceService = deletionTraceService;
        this.targetResolver = targetResolver;
        this.actionExecutor = actionExecutor;
        this.eventPublisher = eventPublisher;
    }

    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @GetMapping("/admin/reports")
    public ResponseEntity<Page<AdminReportResponse>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String needle = normalizeQuery(q);
        Page<ReportEntity> reports;
        Map<UUID, DeletionTraceService.DeletionTrace> traces = Map.of();
        if (deleted) {
            // Corbeille : requête native triée par date de suppression, Pageable non trié.
            // La recherche y couvre description, route d'écran et code du motif.
            reports = reportRepo.findDeletedFiltered(
                    status != null ? status.name() : null,
                    targetType != null ? targetType.name() : null,
                    needle, PageRequest.of(page, size));
            traces = deletionTraceService.latest("REPORT", "REPORT_DELETED",
                    reports.getContent().stream().map(ReportEntity::getId).filter(Objects::nonNull).toList());
        } else if (needle == null) {
            reports = reportRepo.findFiltered(status, targetType, pageable);
        } else {
            List<ReportReason> reasons = reasonsMatching(needle);
            reports = reportRepo.searchFiltered(status, targetType, needle, !reasons.isEmpty(), reasons, pageable);
        }

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

        Set<UUID> packageRequestIds = reports.getContent().stream()
                .filter(r -> r.getTargetType() == ReportTargetType.PACKAGE_REQUEST)
                .map(ReportEntity::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PackageRequestEntity> packageRequestsById = packageRequestIds.isEmpty()
                ? Map.of()
                : packageRequestRepo.findAllById(packageRequestIds).stream()
                        .filter(p -> p.getId() != null)
                        .collect(Collectors.toMap(PackageRequestEntity::getId, Function.identity(), (a, b) -> a));

        Map<UUID, List<String>> photosByReport = reportService.photoUrlsByReport(
                reports.getContent().stream().map(ReportEntity::getId).collect(Collectors.toSet()));

        // Corbeille : ni actions ni auteur (un signalement supprimé ne se traite pas).
        Map<ReportEntity, ResolvedReportTarget> targets = deleted
                ? Map.of()
                : targetResolver.resolve(reports.getContent());
        Set<String> authorities = ReportActionPolicy.authorities(authentication);

        Map<UUID, DeletionTraceService.DeletionTrace> tracesById = traces;
        Page<AdminReportResponse> result = reports.map(r ->
                toResponse(r, usersById, announcementsById, packageRequestsById,
                        photosByReport.getOrDefault(r.getId(), List.of()),
                        r.getId() != null ? tracesById.get(r.getId()) : null,
                        targets.get(r), authorities));
        return ResponseEntity.ok(result);
    }

    /** Détail d'un signalement, avec les actions proposées à l'admin appelant. */
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @GetMapping("/admin/reports/{id}")
    public ResponseEntity<AdminReportResponse> getReport(@PathVariable UUID id, Authentication authentication) {
        ReportEntity report = findOrThrow(id);
        return ResponseEntity.ok(single(report, targetResolver.resolve(report),
                ReportActionPolicy.authorities(authentication)));
    }

    @PreAuthorize("hasAuthority('REPORT_RESOLVE')")
    @PostMapping("/admin/reports/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminReportResponse> resolveReport(
            @PathVariable UUID id,
            @RequestBody ResolveReportRequest request,
            Authentication authentication) {

        ReportEntity report = findOrThrow(id);

        ReportAction action = request.action();
        if (action == null) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "action-required",
                    "Invalid Request", "L'action est obligatoire");
        }
        if (!action.appliesTo(report.getTargetType())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "action-not-applicable",
                    "Unprocessable", "Cette action ne s'applique pas à ce type de cible");
        }
        if (report.getStatus() != ReportStatus.OPEN) {
            // Sans ce garde, une seconde résolution rejouait la sanction et remerciait deux fois.
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "report-already-closed",
                    "Conflict", "Ce signalement est déjà traité");
        }
        // La permission propre au geste AVANT toute lecture de la cible : un 403 ne doit
        // rien apprendre de la cible à l'appelant.
        ReportActionPolicy.requireAuthorities(action, authentication);

        UUID adminId = adminId(authentication);
        ResolvedReportTarget target = targetResolver.resolve(report);
        if (!ReportActionPolicy.isExecutable(action, target)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "report-target-unresolvable",
                    "Unprocessable", "La cible de ce signalement (ou son auteur) est introuvable");
        }
        actionExecutor.apply(id, action, report, target, request.note(), adminId);

        report.setStatus(action.resultingStatus());
        report.setActionTaken(action);
        report.setResolutionNote(request.note());
        report.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reportRepo.save(report);

        Map<String, Object> details = new HashMap<>(Map.of(
                "reportId", id.toString(),
                "action", action.name(),
                "note", request.note() != null ? request.note() : ""));
        if ((action == ReportAction.WARN_AUTHOR || action == ReportAction.SUSPEND_AUTHOR) && target.author() != null) {
            details.put("authorId", String.valueOf(target.author().getId()));
        }
        auditService.log("REPORT", id, "REPORT_RESOLVED", adminId, Map.copyOf(details));

        AdminReportResponse response = single(report, target, ReportActionPolicy.authorities(authentication));

        // Le signalant est remercié (jamais au rejet), s'il existe encore : relu avec les
        // noms de la réponse, un compte supprimé n'y figure pas (@Where deleted_at IS NULL).
        if (report.getStatus() == ReportStatus.RESOLVED && response.reporterName() != null) {
            eventPublisher.publishEvent(new ReportResolvedEvent(id, report.getReporterId()));
        }
        return ResponseEntity.ok(response);
    }

    private ReportEntity findOrThrow(UUID id) {
        return reportRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));
    }

    /** Réponse d'un signalement seul (détail, résolution) : signalant relu, photos présignées. */
    private AdminReportResponse single(ReportEntity report, ResolvedReportTarget target, Set<String> authorities) {
        Map<UUID, UserEntity> singleUser = userRepo.findAllById(
                report.getReporterId() != null ? Set.of(report.getReporterId()) : Set.of()).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return toResponse(report, singleUser, Map.of(), Map.of(),
                reportService.photoUrls(report.getId()), null, target, authorities);
    }

    private UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }

    /**
     * Suppression douce d'un signalement ({@code deleted_at}) : il disparaît des listes
     * (le {@code @Where} de l'entité) mais reste en base avec ses photos et son audit.
     */
    @PreAuthorize("hasAuthority('REPORT_DELETE')")
    @DeleteMapping("/admin/reports/{id}")
    @Transactional
    public ResponseEntity<Void> deleteReport(@PathVariable UUID id, Authentication authentication) {
        ReportEntity report = reportRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));
        softDelete(List.of(report), adminId(authentication), "single");
        return ResponseEntity.noContent().build();
    }

    /**
     * Suppression douce groupée : soit une liste d'identifiants, soit {@code all = true}
     * pour tous les signalements du filtre courant (statut, type, recherche), toutes pages
     * comprises. Répond le nombre réellement supprimé (les identifiants inconnus ou déjà
     * supprimés sont ignorés).
     */
    @PreAuthorize("hasAuthority('REPORT_DELETE')")
    @PostMapping("/admin/reports/bulk-delete")
    @Transactional
    public ResponseEntity<Map<String, Integer>> bulkDeleteReports(
            @RequestBody BulkDeleteReportsRequest request,
            Authentication authentication) {
        boolean all = Boolean.TRUE.equals(request.all());
        List<UUID> ids;
        if (all) {
            String needle = normalizeQuery(request.q());
            List<ReportReason> reasons = needle == null ? List.of() : reasonsMatching(needle);
            ids = reportRepo.findFilteredIds(request.status(), request.targetType(), needle,
                    !reasons.isEmpty(), reasons);
        } else {
            ids = request.ids() != null ? request.ids() : List.of();
        }
        if (ids.isEmpty()) {
            return ResponseEntity.ok(Map.of("deleted", 0));
        }
        int deleted = softDelete(reportRepo.findAllById(ids), adminId(authentication),
                all ? "all-filtered" : "selection");
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /** Corps de {@link #bulkDeleteReports}. */
    public record BulkDeleteReportsRequest(
            List<UUID> ids,
            Boolean all,
            ReportStatus status,
            ReportTargetType targetType,
            String q
    ) {}

    /**
     * Annule la suppression d'un signalement : il revient dans la file avec son statut, sa
     * résolution et ses photos intacts (la suppression n'y touchait pas). Le corps
     * {@code { reason }} est facultatif ; présent, il est validé (10 à 500 caractères) et audité.
     */
    @PreAuthorize("hasAuthority('REPORT_DELETE')")
    @PostMapping("/admin/reports/{id}/restore")
    @Transactional
    public ResponseEntity<AdminReportResponse> restoreReport(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RestoreRequest request,
            Authentication authentication) {
        UUID adminId = adminId(authentication);
        ReportEntity report = reportRepo.findAllByIdIncludingDeleted(List.of(id)).stream()
                .findFirst()
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));
        if (report.getDeletedAt() == null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "report-not-deleted",
                    "Conflict", "Ce signalement n'est pas supprimé");
        }
        restore(report, adminId, "single", request != null ? request.normalizedReason() : "");

        Map<UUID, UserEntity> singleUser = userRepo.findAllById(
                report.getReporterId() != null ? Set.of(report.getReporterId()) : Set.of()).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toResponse(report, singleUser, Map.of(), Map.of(),
                reportService.photoUrls(report.getId()), null, null, Set.of()));
    }

    /**
     * Restauration groupée d'au plus {@value #MAX_BULK_RESTORE} signalements. Les identifiants
     * inconnus ou non supprimés sont ignorés et comptés dans {@code skipped}, sans 409 : une
     * sélection partiellement périmée ne doit pas bloquer le reste.
     */
    @PreAuthorize("hasAuthority('REPORT_DELETE')")
    @PostMapping("/admin/reports/bulk-restore")
    @Transactional
    public ResponseEntity<Map<String, Integer>> bulkRestoreReports(
            @RequestBody BulkRestoreReportsRequest request,
            Authentication authentication) {
        UUID adminId = adminId(authentication);
        List<UUID> ids = request.ids() == null ? List.of()
                : request.ids().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_BULK_RESTORE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bulk-restore-too-many",
                    "Unprocessable Entity",
                    "Au plus " + MAX_BULK_RESTORE + " signalements par restauration groupée");
        }
        if (ids.isEmpty()) {
            return ResponseEntity.ok(Map.of("restored", 0, "skipped", 0));
        }
        int restored = 0;
        for (ReportEntity report : reportRepo.findAllByIdIncludingDeleted(ids)) {
            if (report.getDeletedAt() == null) {
                continue;
            }
            restore(report, adminId, "bulk", "");
            restored++;
        }
        return ResponseEntity.ok(Map.of("restored", restored, "skipped", ids.size() - restored));
    }

    /** Corps de {@link #bulkRestoreReports}. */
    public record BulkRestoreReportsRequest(List<UUID> ids) {}

    private void restore(ReportEntity report, UUID adminId, String mode, String reason) {
        report.setDeletedAt(null);
        reportRepo.save(report);
        auditService.log("REPORT", report.getId(), "REPORT_RESTORED", adminId,
                Map.of("reportId", String.valueOf(report.getId()), "mode", mode, "reason", reason));
    }

    private int softDelete(List<ReportEntity> reports, UUID adminId, String mode) {
        int count = 0;
        for (ReportEntity report : reports) {
            if (report.getDeletedAt() != null) {
                continue;
            }
            report.softDelete();
            reportRepo.save(report);
            auditService.log("REPORT", report.getId(), "REPORT_DELETED", adminId,
                    Map.of(
                            "reportId", String.valueOf(report.getId()),
                            "mode", mode,
                            "status", report.getStatus() != null ? report.getStatus().name() : "",
                            "reason", report.getReason() != null ? report.getReason().name() : ""
                    ));
            count++;
        }
        return count;
    }

    /** {@code q} nettoyé pour un LIKE insensible à la casse, ou null si vide. */
    static String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : "%" + trimmed + "%";
    }

    /** Motifs dont le code ou le libellé contient le texte cherché (needle déjà entouré de %). */
    static List<ReportReason> reasonsMatching(String needle) {
        String bare = needle.substring(1, needle.length() - 1);
        return Arrays.stream(ReportReason.values())
                .filter(r -> r.name().toLowerCase(Locale.ROOT).contains(bare)
                        || r.label().toLowerCase(Locale.ROOT).contains(bare))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AdminReportResponse toResponse(ReportEntity r, Map<UUID, UserEntity> users,
                                           Map<UUID, AnnouncementEntity> announcements,
                                           Map<UUID, PackageRequestEntity> packageRequests,
                                           List<String> photoUrls,
                                           DeletionTraceService.DeletionTrace deletion,
                                           ResolvedReportTarget target,
                                           Set<String> authorities) {
        String reporterName = resolveReporterName(r.getReporterId(), users);
        List<String> availableActions = r.getDeletedAt() != null ? List.of()
                : ReportActionPolicy.availableActions(r, target, authorities).stream().map(Enum::name).toList();
        UserEntity author = target != null ? target.author() : null;
        return new AdminReportResponse(
                r.getId(),
                r.getTargetType() != null ? r.getTargetType().name() : null,
                r.getTargetId(),
                resolveTargetLabel(r, users, announcements, packageRequests),
                reporterName,
                r.getReason() != null ? r.getReason().name() : null,
                r.getDescription(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getActionTaken() != null ? r.getActionTaken().name() : null,
                r.getResolutionNote(),
                r.getResolvedAt(),
                r.getCreatedAt(),
                photoUrls,
                r.getScreenRoute(),
                r.getDeletedAt(),
                r.getDeletedAt() != null && deletion != null ? deletion.adminEmail() : null,
                availableActions,
                author != null ? new AdminReportResponse.TargetAuthor(author.getId(), author.publicDisplayName()) : null
        );
    }

    private String resolveReporterName(UUID reporterId, Map<UUID, UserEntity> users) {
        if (reporterId == null) return null;
        UserEntity u = users.get(reporterId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }

    private String resolveTargetLabel(ReportEntity r, Map<UUID, UserEntity> users,
                                      Map<UUID, AnnouncementEntity> announcements,
                                      Map<UUID, PackageRequestEntity> packageRequests) {
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
            case PACKAGE_REQUEST -> {
                PackageRequestEntity p = packageRequests.get(r.getTargetId());
                yield p != null ? MatchingTextUtil.corridorLabel(p.getDepartureCity(), p.getArrivalCity()) : null;
            }
            case BID, MESSAGE, RATING, APP -> null;
        };
    }
}

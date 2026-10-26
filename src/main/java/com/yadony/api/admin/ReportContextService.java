package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminReportContextResponse;
import com.yadony.api.admin.dto.AdminReportContextResponse.ContextMessage;
import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import com.yadony.api.admin.dto.AdminReportContextResponse.RelatedReport;
import com.yadony.api.admin.dto.AdminReportContextResponse.Reporter;
import com.yadony.api.admin.dto.AdminReportContextResponse.SameScreen;
import com.yadony.api.admin.dto.AdminReportContextResponse.Subject;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.messaging.SystemMessages;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contexte d'un signalement pour son dossier : qui signale, qui est visé, le message dans sa
 * conversation, ce qui a déjà été fait, et les autres signalements sur la même cible. Lecture
 * seule ; chaque morceau est facultatif et son absence ne fait jamais échouer le dossier.
 */
@Service
public class ReportContextService {

    /** Messages montrés avant et après le message signalé : de quoi lire l'échange sans lire la conversation. */
    static final int MESSAGES_BEFORE = 2;
    static final int MESSAGES_AFTER = 2;
    static final int HISTORY_LIMIT = 30;

    private final ReportRepository reportRepo;
    private final UserRepository userRepo;
    private final ReportTargetResolver targetResolver;
    private final FirestoreService firestoreService;
    private final AuditLogRepository auditLogRepo;
    private final AdminUserRepository adminUserRepo;
    private final ReportTargetPreviewBuilder previewBuilder;

    public ReportContextService(ReportRepository reportRepo,
                                UserRepository userRepo,
                                ReportTargetResolver targetResolver,
                                FirestoreService firestoreService,
                                AuditLogRepository auditLogRepo,
                                AdminUserRepository adminUserRepo,
                                ReportTargetPreviewBuilder previewBuilder) {
        this.reportRepo = reportRepo;
        this.userRepo = userRepo;
        this.targetResolver = targetResolver;
        this.firestoreService = firestoreService;
        this.auditLogRepo = auditLogRepo;
        this.adminUserRepo = adminUserRepo;
        this.previewBuilder = previewBuilder;
    }

    /**
     * @param canReadConversations l'appelant a {@code MODERATION_VIEW} : seul droit qui ouvre déjà
     *                             la lecture des conversations ; sans lui, pas de messages voisins
     */
    public AdminReportContextResponse build(ReportEntity report, boolean canReadConversations) {
        ResolvedReportTarget target = targetResolver.resolve(report);
        UserEntity subjectUser = target.author();

        boolean isMessage = report.getTargetType() == ReportTargetType.MESSAGE;
        boolean readable = isMessage && target.messageResolved() && canReadConversations;
        return new AdminReportContextResponse(
                reporter(report),
                subject(subjectUser),
                readable ? messages(target, subjectUser) : null,
                isMessage && !canReadConversations,
                history("REPORT", report.getId()),
                related(report),
                previewBuilder.build(report),
                sameScreen(report));
    }

    private Reporter reporter(ReportEntity report) {
        UUID reporterId = report.getReporterId();
        if (reporterId == null) {
            return null;
        }
        return userRepo.findById(reporterId)
                .map(u -> new Reporter(
                        u.getId(), MatchingTextUtil.buildName(u), u.getCreatedAt(),
                        reportRepo.countByReporterId(reporterId),
                        reportRepo.countByReporterIdAndStatus(reporterId, ReportStatus.RESOLVED),
                        reportRepo.countByReporterIdAndStatus(reporterId, ReportStatus.DISMISSED)))
                .orElse(null);
    }

    private Subject subject(UserEntity user) {
        if (user == null) {
            return null;
        }
        return new Subject(
                user.getId(), MatchingTextUtil.buildName(user), user.getCreatedAt(),
                user.getStatus() != null ? user.getStatus().name() : null,
                user.getKycStatus() == KycStatus.VERIFIED,
                reportRepo.countReceivedByUser(user.getId()),
                reportRepo.countReceivedPending(user.getId()),
                user.getCancellationCount());
    }

    private List<ContextMessage> messages(ResolvedReportTarget target, UserEntity subjectUser) {
        List<Map<String, Object>> raw = firestoreService.listMessagesAround(
                target.conversation().getFirestoreConversationId(), target.messageId(),
                MESSAGES_BEFORE, MESSAGES_AFTER);
        if (raw.isEmpty()) {
            return null;
        }
        Set<String> senderIds = raw.stream()
                .map(m -> (String) m.get("senderId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, UserEntity> senders = MessageSenders.resolve(userRepo, senderIds);
        return raw.stream().map(m -> {
            String senderId = (String) m.get("senderId");
            UserEntity sender = senderId != null ? senders.get(senderId) : null;
            boolean system = senderId != null && SystemMessages.isSystemSender(senderId);
            String id = (String) m.get("id");
            return new ContextMessage(
                    id,
                    system ? "Système" : sender != null ? MatchingTextUtil.buildName(sender) : null,
                    (String) m.getOrDefault("body", ""),
                    m.get("sentAt") != null ? m.get("sentAt").toString() : null,
                    Objects.equals(id, target.messageId()),
                    m.get("deletedAt") != null,
                    sender != null && subjectUser != null && Objects.equals(sender.getId(), subjectUser.getId()));
        }).toList();
    }

    /**
     * Gestes tracés sur une entité (signalement, avis), du plus récent au plus ancien, avec l'e-mail
     * du compte admin. Partagé avec le contexte d'un avis.
     */
    List<HistoryEntry> history(String entityType, UUID entityId) {
        if (entityId == null) {
            return List.of();
        }
        List<AuditLogEntity> entries = auditLogRepo
                .findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(entityType, entityId,
                        PageRequest.of(0, HISTORY_LIMIT))
                .getContent();
        Set<UUID> actorIds = entries.stream().map(AuditLogEntity::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> emails = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (AdminUserEntity admin : adminUserRepo.findAllById(actorIds)) {
                emails.put(admin.getId(), admin.getEmail());
            }
        }
        return entries.stream().map(e -> {
            Map<String, Object> payload = e.getPayload() != null ? e.getPayload() : Map.of();
            Object note = payload.get("note") != null ? payload.get("note") : payload.get("reason");
            Object action = payload.get("action");
            // Un avis réintégré dans la moyenne se trace comme une exclusion avec `excluded=false`.
            String detail = action != null ? action.toString()
                    : "false".equals(String.valueOf(payload.get("excluded"))) ? "REINTEGRATED" : null;
            return new HistoryEntry(
                    e.getAction(),
                    detail,
                    e.getCreatedAt(),
                    e.getActorId() != null ? emails.get(e.getActorId()) : null,
                    note != null && !note.toString().isBlank() ? note.toString() : null);
        }).toList();
    }

    private List<RelatedReport> related(ReportEntity report) {
        if (report.getId() == null || report.getTargetId() == null || report.getTargetType() == null
                || report.getTargetType() == ReportTargetType.APP) {
            return List.of();
        }
        return toRelated(reportRepo.findTop10ByTargetTypeAndTargetIdAndIdNotOrderByCreatedAtDesc(
                report.getTargetType(), report.getTargetId(), report.getId()));
    }

    /** Retours du scarabée sur le même écran : plusieurs sur une même route disent un vrai problème. */
    private SameScreen sameScreen(ReportEntity report) {
        String route = report.getScreenRoute();
        if (report.getReason() != ReportReason.SCREEN_BUG || route == null || route.isBlank()) {
            return null;
        }
        return new SameScreen(
                route,
                reportRepo.countByReasonAndScreenRoute(ReportReason.SCREEN_BUG, route),
                reportRepo.countByReasonAndScreenRouteAndStatus(ReportReason.SCREEN_BUG, route, ReportStatus.OPEN),
                toRelated(reportRepo.findTop5ByReasonAndScreenRouteAndIdNotOrderByCreatedAtDesc(
                        ReportReason.SCREEN_BUG, route, report.getId())));
    }

    /** Signalements voisins avec le nom de leur signalant ; partagé avec le contexte d'un avis. */
    List<RelatedReport> toRelated(List<ReportEntity> others) {
        if (others.isEmpty()) {
            return List.of();
        }
        Set<UUID> reporterIds = others.stream().map(ReportEntity::getReporterId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserEntity> reporters = userRepo.findAllById(reporterIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return others.stream().map(o -> new RelatedReport(
                o.getId(),
                o.getReason() != null ? o.getReason().name() : null,
                o.getStatus() != null ? o.getStatus().name() : null,
                o.getCreatedAt(),
                o.getReporterId() != null && reporters.containsKey(o.getReporterId())
                        ? MatchingTextUtil.buildName(reporters.get(o.getReporterId())) : null)).toList();
    }
}

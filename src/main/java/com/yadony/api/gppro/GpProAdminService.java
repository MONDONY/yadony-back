package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.gppro.dto.AdminGpProApplicationDetail;
import com.yadony.api.gppro.dto.AdminGpProApplicationRow;
import com.yadony.api.gppro.dto.AdminGpProReviewRow;
import com.yadony.api.gppro.events.GpProDecisionEvent;
import com.yadony.api.gppro.events.GpProStatusChangedEvent;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Décisions de l'admin sur le badge GP Pro : file, détail, validation, refus, attribution
 * directe et retrait.
 *
 * <p>Chaque décision verrouille la ligne de l'utilisateur (comme la demande côté utilisateur) et
 * relit l'entité renvoyée par le verrou : {@code decide} vide le contexte de persistance.
 */
@Service
public class GpProAdminService {

    private static final String ENTITY = "GP_PRO_APPLICATION";
    private static final Duration DOCUMENT_URL_TTL = Duration.ofMinutes(15);
    private static final Set<GpProApplicationStatus> OPEN =
            EnumSet.of(GpProApplicationStatus.AWAITING_KYC, GpProApplicationStatus.PENDING);
    private static final Set<AnnouncementStatus> PUBLISHED =
            EnumSet.of(AnnouncementStatus.ACTIVE, AnnouncementStatus.FULL,
                    AnnouncementStatus.IN_PROGRESS, AnnouncementStatus.COMPLETED);

    private final GpProApplicationRepository applications;
    private final GpProDocumentRepository documents;
    private final GpProDocumentService documentService;
    private final GpProReviewFlagRepository flags;
    private final UserRepository users;
    private final AnnouncementRepository announcements;
    private final BidRepository bids;
    private final RatingRepository ratings;
    private final DisputeRepository disputes;
    private final ReportRepository reports;
    private final StorageService storage;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public GpProAdminService(GpProApplicationRepository applications, GpProDocumentRepository documents,
                             GpProDocumentService documentService, GpProReviewFlagRepository flags,
                             UserRepository users, AnnouncementRepository announcements,
                             BidRepository bids,
                             RatingRepository ratings, DisputeRepository disputes,
                             ReportRepository reports, StorageService storage, AuditService audit,
                             ApplicationEventPublisher events) {
        this.applications = applications;
        this.documents = documents;
        this.documentService = documentService;
        this.flags = flags;
        this.users = users;
        this.announcements = announcements;
        this.bids = bids;
        this.ratings = ratings;
        this.disputes = disputes;
        this.reports = reports;
        this.storage = storage;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<AdminGpProApplicationRow> list(GpProApplicationStatus status, int page, int size) {
        Page<GpProApplicationEntity> result = applications.findByStatusOrderByCreatedAtAsc(status,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        Map<UUID, UserEntity> byId = users.findAllById(
                        result.stream().map(GpProApplicationEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<UUID, Integer> docCounts = result.isEmpty() ? Map.of()
                : documents.countUnpurgedByApplicationIds(
                                result.stream().map(GpProApplicationEntity::getId).toList())
                        .stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> ((Number) r[1]).intValue()));
        return result.map(a -> new AdminGpProApplicationRow(a.getId(), a.getUserId(),
                displayName(byId.get(a.getUserId())), a.getSource(), a.getTravelFrequency(),
                docCounts.getOrDefault(a.getId(), 0), a.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public AdminGpProApplicationDetail detail(UUID applicationId) {
        GpProApplicationEntity app = applications.findById(applicationId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "gp-pro-application-not-found", "Application Not Found", "Demande introuvable"));
        UUID userId = app.getUserId();
        UserEntity user = users.findById(userId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Integer> lastFive = ratings.findRecentIncludedRatings(userId).stream()
                .limit(5).map(RatingEntity::getStars).toList();
        List<AdminGpProApplicationDetail.Doc> docs = documents.findByApplicationIdAndPurgedAtIsNull(applicationId)
                .stream()
                .filter(d -> d.getStorageKey() != null)
                .map(d -> new AdminGpProApplicationDetail.Doc(d.getId(), d.getDocType(),
                        storage.generatePresignedUrl(d.getStorageKey(), DOCUMENT_URL_TTL)))
                .toList();
        return new AdminGpProApplicationDetail(
                app.getId(), userId, displayName(user), app.getStatus(), app.getSource(),
                app.getTravelFrequency(), app.getCommercialName(), app.getCreatedAt(),
                user == null || user.getKycStatus() == null ? null : user.getKycStatus().name(),
                announcements.countByTravelerIdAndStatusIn(userId, PUBLISHED),
                announcements.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                        userId, now.minusDays(56), now, AnnouncementStatus.DRAFT),
                bids.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.COMPLETED),
                user == null ? null : user.getAverageRating(),
                lastFive,
                disputes.countByTravelerIdAndStatus(userId, "OPEN"),
                reports.countByStatusAndTargetTypeAndTargetId(ReportStatus.OPEN, ReportTargetType.USER, userId),
                user == null ? null : user.getCreatedAt(),
                docs);
    }

    @Transactional(readOnly = true)
    public Page<AdminGpProReviewRow> reviewQueue(int page, int size) {
        Page<UUID> userIds = flags.findReviewUserIds(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        if (userIds.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), userIds.getPageable(), 0);
        }
        Map<UUID, UserEntity> byId = users.findAllById(userIds.getContent())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<UUID, List<GpProReviewFlagEntity>> byUser = flags
                .findByUserIdInAndResolvedAtIsNullAndDismissedAtIsNull(userIds.getContent())
                .stream().collect(Collectors.groupingBy(GpProReviewFlagEntity::getUserId));
        return userIds.map(id -> {
            List<GpProReviewFlagEntity> mine = byUser.getOrDefault(id, List.of()).stream()
                    .sorted(java.util.Comparator.comparing(GpProReviewFlagEntity::getReason)).toList();
            LocalDateTime detectedAt = mine.stream().map(GpProReviewFlagEntity::getDetectedAt)
                    .min(java.util.Comparator.naturalOrder()).orElse(null);
            return new AdminGpProReviewRow(id, displayName(byId.get(id)),
                    mine.stream().map(GpProReviewFlagEntity::getReason).toList(), detectedAt,
                    mine.stream().map(f -> new AdminGpProReviewRow.Flag(f.getId(), f.getReason())).toList());
        });
    }

    /** Écarte un signalement de revue ; sans effet s'il l'est déjà. Un signalement clos ne s'écarte plus. */
    @Transactional
    public void dismissFlag(UUID flagId, UUID adminId) {
        GpProReviewFlagEntity flag = flags.findById(flagId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "gp-pro-flag-not-found", "Flag Not Found", "Signalement de revue introuvable"));
        if (flag.getResolvedAt() != null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "gp-pro-flag-resolved", "Flag Resolved", "Ce signalement de revue est déjà clos");
        }
        if (flag.getDismissedAt() != null) {
            return;
        }
        flag.setDismissedAt(LocalDateTime.now(ZoneOffset.UTC));
        flag.setDismissedBy(adminId);
        flags.save(flag);
        audit.log("GP_PRO_REVIEW_FLAG", flagId, "GP_PRO_REVIEW_DISMISSED", adminId,
                Map.of("userId", flag.getUserId().toString(), "reason", flag.getReason().name()));
    }

    @Transactional
    public void approve(UUID applicationId, UUID adminId) {
        UUID userId = applicationOf(applicationId).getUserId();
        assertEligible(lockedUser(userId));
        decideOrConflict(applicationId, GpProApplicationStatus.APPROVED, adminId, null);
        UserEntity user = lockedUser(userId);
        grantBadge(user);
        users.save(user);
        audit.log(ENTITY, applicationId, "GP_PRO_APPROVED", adminId, Map.of("userId", userId.toString()));
        purgeDocuments(applicationId, adminId);
        events.publishEvent(new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.APPROVED, null));
        events.publishEvent(new GpProStatusChangedEvent(userId, true));
    }

    @Transactional
    public void reject(UUID applicationId, UUID adminId, String rawReason) {
        String reason = requireReason(rawReason);
        UUID userId = applicationOf(applicationId).getUserId();
        users.findByIdForUpdate(userId);
        decideOrConflict(applicationId, GpProApplicationStatus.REJECTED, adminId, reason);
        audit.log(ENTITY, applicationId, "GP_PRO_REJECTED", adminId,
                Map.of("userId", userId.toString(), "reason", reason));
        purgeDocuments(applicationId, adminId);
        events.publishEvent(new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.REJECTED, reason));
    }

    @Transactional
    public void grant(UUID userId, UUID adminId, String note) {
        UserEntity user = lockedUser(userId);
        if (user.isGpPro()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "gp-pro-already-active", "Already Active", "Le badge GP Pro est déjà actif");
        }
        assertEligible(user);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        GpProApplicationEntity app = applications.findFirstByUserIdAndStatusIn(userId, OPEN).orElse(null);
        if (app == null) {
            app = new GpProApplicationEntity();
            app.setUserId(userId);
            app.setSource(GpProApplicationSource.ADMIN);
        }
        app.setStatus(GpProApplicationStatus.APPROVED);
        app.setDecidedBy(adminId);
        app.setDecidedAt(now);
        app.setDecisionReason(cleanNote);
        app = applications.saveAndFlush(app);
        grantBadge(user);
        users.save(user);
        audit.log(ENTITY, app.getId(), "GP_PRO_GRANTED_DIRECTLY", adminId, Map.of("userId", userId.toString()));
        purgeDocuments(app.getId(), adminId);
        events.publishEvent(new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.APPROVED, null));
        events.publishEvent(new GpProStatusChangedEvent(userId, true));
    }

    @Transactional
    public void revoke(UUID userId, UUID adminId, String rawReason) {
        String reason = requireReason(rawReason);
        UserEntity user = lockedUser(userId);
        if (!user.isGpPro()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "gp-pro-not-active", "Not Active", "Le badge GP Pro n'est pas actif");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setGpPro(false);
        user.setGpProRevokedAt(now);
        user.setGpProRevokedReason(reason);
        users.save(user);
        List<GpProReviewFlagEntity> active = flags.findByUserIdAndResolvedAtIsNull(userId);
        active.forEach(f -> f.setResolvedAt(now));
        flags.saveAll(active);
        audit.log("USER", userId, "GP_PRO_REVOKED", adminId, Map.of("reason", reason));
        events.publishEvent(new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.REVOKED, reason));
        events.publishEvent(new GpProStatusChangedEvent(userId, false));
    }

    /** Gardes communes à la validation et à l'attribution directe, avant toute écriture. */
    private static void assertEligible(UserEntity user) {
        if (user.getKycStatus() != KycStatus.VERIFIED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-kyc-required", "KYC Required", "La vérification d'identité est requise");
        }
        if (user.getStatus() != UserStatus.ACTIVE || user.getDeletionRequestedAt() != null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "gp-pro-user-not-eligible", "User Not Eligible",
                    "Le compte est suspendu, banni ou en cours de suppression");
        }
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-reason-required", "Reason Required", "Un motif est obligatoire");
        }
        return trimmed;
    }

    private GpProApplicationEntity applicationOf(UUID applicationId) {
        return applications.findById(applicationId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "gp-pro-application-not-found", "Application Not Found", "Demande introuvable"));
    }

    private void decideOrConflict(UUID applicationId, GpProApplicationStatus status, UUID adminId, String reason) {
        int updated = applications.decide(applicationId, status, adminId, reason,
                LocalDateTime.now(ZoneOffset.UTC));
        if (updated == 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "gp-pro-application-not-pending", "Application Not Pending",
                    "La demande a déjà été décidée");
        }
    }

    private UserEntity lockedUser(UUID userId) {
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private static void grantBadge(UserEntity user) {
        user.setGpPro(true);
        user.setGpProGrantedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setGpProRevokedAt(null);
        user.setGpProRevokedReason(null);
    }

    /** Un échec du stockage ne défait pas la décision : la clé reste, le rapprochement repassera. */
    private void purgeDocuments(UUID applicationId, UUID adminId) {
        int purged = documentService.purgeAll(applicationId);
        audit.log(ENTITY, applicationId, "GP_PRO_DOCUMENTS_PURGED", adminId, Map.of("count", purged));
    }

    private static String displayName(UserEntity user) {
        return user == null ? UserEntity.UNKNOWN_DISPLAY_NAME : user.adminDisplayName();
    }
}

package com.yadony.api.signalements;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.ratings.RatingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signalements créés par les utilisateurs de l'app (incidents, contenus abusifs…)
 * avec captures d'écran jointes. Lecture / résolution côté admin.
 */
@Service
public class ReportService {

    static final int MAX_PHOTOS = 4;
    static final String PHOTO_PREFIX = "reports/";
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final ReportRepository reportRepository;
    private final ReportPhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final RatingRepository ratingRepository;
    private final StorageService storageService;
    private final AuditService auditService;

    public ReportService(ReportRepository reportRepository,
                         ReportPhotoRepository photoRepository,
                         UserRepository userRepository,
                         AnnouncementRepository announcementRepository,
                         BidRepository bidRepository,
                         RatingRepository ratingRepository,
                         StorageService storageService,
                         AuditService auditService) {
        this.reportRepository = reportRepository;
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.ratingRepository = ratingRepository;
        this.storageService = storageService;
        this.auditService = auditService;
    }

    /** Upload d'une capture d'écran sous reports/{userId}/ ; renvoie la clé S3. */
    public String uploadPhoto(String firebaseUid, MultipartFile file) {
        UserEntity user = requireUser(firebaseUid);
        try {
            return storageService.uploadFile(file, PHOTO_PREFIX + user.getId() + "/");
        } catch (IOException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "photo-upload-failed", "Photo Upload Failed",
                    "Impossible d'enregistrer la capture d'écran");
        }
    }

    /** Crée un signalement + attache jusqu'à MAX_PHOTOS captures déjà uploadées. */
    @Transactional
    public ReportEntity createReport(String firebaseUid,
                                     ReportTargetType targetType,
                                     UUID targetId,
                                     ReportReason reason,
                                     String description,
                                     List<String> photoKeys) {
        UserEntity reporter = requireUser(firebaseUid);

        if (targetType == null) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "target-type-required",
                    "Invalid Request", "Le type de cible est obligatoire");
        }
        if (targetType != ReportTargetType.APP && targetId == null) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "target-id-required",
                    "Invalid Request", "L'identifiant de la cible est obligatoire pour ce type de signalement");
        }
        if (reason == null) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "reason-required",
                    "Invalid Request", "Le motif est obligatoire");
        }
        if (!reason.appliesTo(targetType)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "reason-not-applicable",
                    "Unprocessable", "Ce motif ne s'applique pas à ce type de signalement");
        }
        if (targetType == ReportTargetType.USER && targetId != null && targetId.equals(reporter.getId())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-report-self",
                    "Unprocessable", "Vous ne pouvez pas vous signaler vous-même");
        }
        requireTargetExists(targetType, targetId);

        List<String> keys = photoKeys != null ? photoKeys : List.of();
        if (keys.size() > MAX_PHOTOS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-photos",
                    "Too Many Photos", "Maximum " + MAX_PHOTOS + " captures d'écran par signalement");
        }
        String ownPrefix = PHOTO_PREFIX + reporter.getId() + "/";
        for (String key : keys) {
            if (key == null || !key.startsWith(ownPrefix)) {
                throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "photo-not-owned",
                        "Forbidden", "Une des captures ne vous appartient pas");
            }
        }

        ReportEntity report = new ReportEntity();
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReporterId(reporter.getId());
        report.setReason(reason);
        report.setDescription(description);
        report.setStatus(ReportStatus.OPEN);
        reportRepository.save(report);

        for (String key : keys) {
            ReportPhotoEntity photo = new ReportPhotoEntity();
            photo.setReportId(report.getId());
            photo.setObjectKey(key);
            photoRepository.save(photo);
        }

        auditService.log("REPORT", report.getId(), "REPORT_CREATED", reporter.getId(),
                Map.of(
                        "targetType", targetType.name(),
                        "targetId", Objects.toString(targetId, ""),
                        "reason", report.getReason().name(),
                        "photoCount", keys.size()
                ));

        return report;
    }

    /**
     * MESSAGE n'est volontairement pas vérifié : {@code target_id} est un {@code UUID} alors
     * que les identifiants de message vivent côté Firestore (chaînes), pas dans une table
     * relationnelle référençable ici. Vérifier l'existence pour ce type nécessiterait de
     * faire porter au modèle une colonne qu'il ne peut pas représenter correctement — hors
     * périmètre de ce correctif.
     */
    private void requireTargetExists(ReportTargetType targetType, UUID targetId) {
        if (targetId == null) {
            return;
        }
        boolean exists = switch (targetType) {
            case USER -> userRepository.existsById(targetId);
            case ANNOUNCEMENT -> announcementRepository.existsById(targetId);
            case BID -> bidRepository.existsById(targetId);
            case RATING -> ratingRepository.existsById(targetId);
            case MESSAGE, APP -> true;
        };
        if (!exists) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "target-not-found",
                    "Not Found", "La cible du signalement est introuvable");
        }
    }

    /** URLs présignées des captures d'un signalement (lecture admin). */
    public List<String> photoUrls(UUID reportId) {
        return photoRepository.findByReportIdOrderByCreatedAtAsc(reportId).stream()
                .map(p -> storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL))
                .toList();
    }

    /** URLs présignées par signalement, en une requête (listes admin — évite le N+1). */
    public Map<UUID, List<String>> photoUrlsByReport(Collection<UUID> reportIds) {
        if (reportIds.isEmpty()) return Map.of();
        return photoRepository.findByReportIdInOrderByCreatedAtAsc(reportIds).stream()
                .collect(Collectors.groupingBy(
                        ReportPhotoEntity::getReportId,
                        Collectors.mapping(
                                p -> storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL),
                                Collectors.toList())));
    }

    private UserEntity requireUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "Unauthorized", "Utilisateur introuvable"));
    }
}

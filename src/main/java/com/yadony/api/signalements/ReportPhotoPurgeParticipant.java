package com.yadony.api.signalements;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import com.yadony.api.common.media.PhotoPurgeParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Photos d'un signalement clos (RESOLVED/DISMISSED) depuis N jours : supprimées de R2, ligne
 * soft-deleted. Un signalement clos ne se rouvre pas (l'admin répond 409 « déjà traité »), la
 * purge est donc sans retour en arrière possible. Les tickets support en gardent une copie.
 */
@Component
public class ReportPhotoPurgeParticipant implements PhotoPurgeParticipant {

    private static final Logger log = LoggerFactory.getLogger(ReportPhotoPurgeParticipant.class);
    private final ReportPhotoRepository photos;
    private final StorageService storage;
    private final AuditService audit;

    public ReportPhotoPurgeParticipant(ReportPhotoRepository photos, StorageService storage, AuditService audit) {
        this.photos = photos;
        this.storage = storage;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "reports";
    }

    @Override
    @Transactional
    public PhotoPurgeOutcome purgeDue(int retentionDays, boolean dryRun, int limit, LocalDateTime now) {
        List<ReportPhotoEntity> due = photos.findLiveOfClosedReportsBefore(
                now.minusDays(retentionDays).atOffset(ZoneOffset.UTC), PageRequest.of(0, limit));
        if (dryRun) return new PhotoPurgeOutcome(due.size(), 0, 0);
        int deleted = 0;
        int failed = 0;
        for (ReportPhotoEntity p : due) {
            try {
                storage.deleteFile(p.getObjectKey());
            } catch (NoSuchKeyException ignored) {
                // déjà absent : on marque quand même la ligne
            } catch (RuntimeException e) {
                log.warn("ReportPhotoPurge: suppression R2 en échec pour {} : {}", p.getObjectKey(), e.toString());
                failed++;
                continue;
            }
            p.softDelete();
            photos.save(p);
            deleted++;
        }
        if (deleted > 0) {
            // audit_log.entity_id est nullable (V7) : une entrée de lot suffit.
            audit.log("report_photo", null, "REPORT_PHOTOS_PURGED", null,
                    Map.of("count", deleted, "reason", "RETENTION_EXPIRED"));
        }
        return new PhotoPurgeOutcome(due.size(), deleted, failed);
    }
}

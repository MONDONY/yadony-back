package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import com.yadony.api.common.media.PhotoPurgeParticipant;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidMediaRetention;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestPhotoRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Photos d'une demande d'envoi : purgées quand la demande est close et tous ses bids échus. */
@Component
public class PackageRequestPhotoPurgeParticipant implements PhotoPurgeParticipant {

    private static final Logger log = LoggerFactory.getLogger(PackageRequestPhotoPurgeParticipant.class);
    /**
     * Statuts définitifs. REMOVED_BY_ADMIN est exclu : la restauration admin
     * (restoreByAdmin) remet la demande à son statut d'avant sans limite de délai, et une
     * suppression R2 est irréversible. Une demande retirée garde donc ses photos, qui
     * restent référencées (le rapprochement ne les touche pas).
     */
    static final Set<PackageRequestStatus> CLOSED = EnumSet.of(PackageRequestStatus.COMPLETED,
            PackageRequestStatus.EXPIRED, PackageRequestStatus.CANCELLED);

    private final PackageRequestPhotoRepository photos;
    private final PackageRequestRepository requests;
    private final NegotiationThreadRepository threads;
    private final BidRepository bids;
    private final StorageService storage;
    private final AuditService audit;
    private final List<MessagingMediaRetentionHold> holds;

    public PackageRequestPhotoPurgeParticipant(PackageRequestPhotoRepository photos, PackageRequestRepository requests,
                                               NegotiationThreadRepository threads, BidRepository bids,
                                               StorageService storage, AuditService audit,
                                               List<MessagingMediaRetentionHold> holds) {
        this.photos = photos;
        this.requests = requests;
        this.threads = threads;
        this.bids = bids;
        this.storage = storage;
        this.audit = audit;
        this.holds = holds;
    }

    @Override
    public String name() {
        return "package_requests";
    }

    @Override
    @Transactional
    public PhotoPurgeOutcome purgeDue(int retentionDays, boolean dryRun, int limit, LocalDateTime now) {
        PhotoPurgeOutcome total = PhotoPurgeOutcome.NONE;
        for (UUID requestId : photos.findRequestIdsWithLivePhotos()) {
            if (total.eligible() >= limit) break;
            if (!isDue(requestId, retentionDays, now)) continue;
            List<PackageRequestPhotoEntity> rows = photos.findByPackageRequestIdOrderByPositionAsc(requestId);
            int deleted = 0;
            int failed = 0;
            for (PackageRequestPhotoEntity p : rows) {
                if (dryRun) continue;
                if (deleteObject(p.getObjectKey())) {
                    p.markPurged(now);
                    photos.save(p);
                    deleted++;
                } else {
                    failed++;
                }
            }
            if (deleted > 0) {
                audit.log("package_request", requestId, "PACKAGE_REQUEST_PHOTOS_PURGED", null,
                        Map.of("count", deleted, "reason", "RETENTION_EXPIRED"));
            }
            total = total.plus(new PhotoPurgeOutcome(rows.size(), deleted, failed));
        }
        return total;
    }

    private boolean isDue(UUID requestId, int days, LocalDateTime now) {
        // findById est filtré par @SQLRestriction : une demande absente ou soft-deleted
        // est traitée comme échue côté demande, ses bids matérialisés restent vérifiés.
        PackageRequestEntity request = requests.findById(requestId).orElse(null);
        if (request != null) {
            if (!CLOSED.contains(request.getStatus())) return false;
            if (request.getUpdatedAt() == null || now.isBefore(request.getUpdatedAt().plusDays(days))) return false;
        }
        for (NegotiationThreadEntity t : threads.findByPackageRequestId(requestId)) {
            UUID bidId = t.getMaterializedBidId();
            if (bidId == null) continue;
            Optional<BidEntity> bid = bids.findById(bidId);
            if (bid.isPresent()) {
                Optional<LocalDateTime> end = BidMediaRetention.end(bid.get(), days);
                if (end.isEmpty() || now.isBefore(end.get())) return false;
            }
            if (holds.stream().anyMatch(h -> h.holds(bidId, List.of()))) return false;
        }
        return true;
    }

    /** Vrai si l'objet n'existe plus dans R2 (supprimé ou déjà absent, ou URL externe héritée). */
    private boolean deleteObject(String key) {
        if (key == null || key.startsWith("http://") || key.startsWith("https://")) return true;
        try {
            storage.deleteFile(key);
            return true;
        } catch (NoSuchKeyException e) {
            return true;
        } catch (RuntimeException e) {
            log.warn("PackageRequestPhotoPurge: suppression R2 en échec pour {} : {}", key, e.toString());
            return false;
        }
    }
}

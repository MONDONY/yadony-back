package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.PackageRequestMediaRetentionHold;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Photos d'une demande d'envoi : purgées quand la demande est close et tous ses bids échus.
 * Aucune transaction autour des appels R2 (connexion Hikari tenue pendant le réseau) :
 * lectures en autocommit, R2 hors transaction, marquage et audit de la demande en transaction courte.
 */
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
    private final List<PackageRequestMediaRetentionHold> requestHolds;
    private final TransactionTemplate tx;

    public PackageRequestPhotoPurgeParticipant(PackageRequestPhotoRepository photos, PackageRequestRepository requests,
                                               NegotiationThreadRepository threads, BidRepository bids,
                                               StorageService storage, AuditService audit,
                                               List<MessagingMediaRetentionHold> holds,
                                               List<PackageRequestMediaRetentionHold> requestHolds,
                                               PlatformTransactionManager transactionManager) {
        this.photos = photos;
        this.requests = requests;
        this.threads = threads;
        this.bids = bids;
        this.storage = storage;
        this.audit = audit;
        this.holds = holds;
        this.requestHolds = requestHolds;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "package_requests";
    }

    @Override
    public PhotoPurgeOutcome purgeDue(int retentionDays, boolean dryRun, int limit, LocalDateTime now) {
        PhotoPurgeOutcome total = PhotoPurgeOutcome.NONE;
        for (UUID requestId : photos.findRequestIdsWithLivePhotos()) {
            if (total.eligible() >= limit) break;
            if (!isDue(requestId, retentionDays, now)) continue;
            List<PackageRequestPhotoEntity> rows = photos.findByPackageRequestIdOrderByPositionAsc(requestId);
            List<PackageRequestPhotoEntity> gone = new ArrayList<>();
            int failed = 0;
            for (PackageRequestPhotoEntity p : rows) {
                if (dryRun) continue;
                if (deleteObject(p.getObjectKey())) {
                    gone.add(p);
                } else {
                    failed++;
                }
            }
            if (!gone.isEmpty()) {
                // Marquage échoué = lignes reprises au passage suivant (objet absent = supprimé).
                tx.executeWithoutResult(status -> {
                    for (PackageRequestPhotoEntity p : gone) {
                        p.markPurged(now);
                        photos.save(p);
                    }
                    audit.log("package_request", requestId, "PACKAGE_REQUEST_PHOTOS_PURGED", null,
                            Map.of("count", gone.size(), "reason", "RETENTION_EXPIRED"));
                });
            }
            total = total.plus(new PhotoPurgeOutcome(rows.size(), gone.size(), failed));
        }
        return total;
    }

    private boolean isDue(UUID requestId, int days, LocalDateTime now) {
        // Fermé par défaut : une demande soft-deleted (ou absente) peut porter un litige ou un
        // signalement ; ses photos restent référencées, le rapprochement ne les touche pas.
        PackageRequestEntity request = requests.findById(requestId).orElse(null);
        if (request == null) return false;
        if (!CLOSED.contains(request.getStatus())) return false;
        if (request.getUpdatedAt() == null || now.isBefore(request.getUpdatedAt().plusDays(days))) return false;
        if (requestHolds.stream().anyMatch(h -> h.holds(requestId))) return false;
        for (NegotiationThreadEntity t : threads.findByPackageRequestId(requestId)) {
            UUID bidId = t.getMaterializedBidId();
            if (bidId == null) continue;
            Optional<BidEntity> bid = bids.findById(bidId);
            // Bid matérialisé introuvable (soft-deleted) : fermé par défaut, on garde.
            if (bid.isEmpty()) return false;
            Optional<LocalDateTime> end = BidMediaRetention.end(bid.get(), days);
            if (end.isEmpty() || now.isBefore(end.get())) return false;
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

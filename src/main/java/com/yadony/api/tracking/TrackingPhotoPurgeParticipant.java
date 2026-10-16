package com.yadony.api.tracking;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import com.yadony.api.common.media.PhotoPurgeParticipant;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidMediaRetention;
import com.yadony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Photos de scan (départ, transit, livraison) : purgées N jours après la livraison du bid.
 * Aucune transaction autour des appels R2 (connexion Hikari tenue pendant le réseau) :
 * lectures en autocommit, R2 hors transaction, marquage et audit du bid en transaction courte.
 */
@Component
public class TrackingPhotoPurgeParticipant implements PhotoPurgeParticipant {

    private static final Logger log = LoggerFactory.getLogger(TrackingPhotoPurgeParticipant.class);

    private final TrackingEventRepository events;
    private final BidRepository bids;
    private final StorageService storage;
    private final AuditService audit;
    private final List<MessagingMediaRetentionHold> holds;
    private final TransactionTemplate tx;

    public TrackingPhotoPurgeParticipant(TrackingEventRepository events, BidRepository bids,
                                         StorageService storage, AuditService audit,
                                         List<MessagingMediaRetentionHold> holds,
                                         PlatformTransactionManager transactionManager) {
        this.events = events;
        this.bids = bids;
        this.storage = storage;
        this.audit = audit;
        this.holds = holds;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "tracking";
    }

    @Override
    public PhotoPurgeOutcome purgeDue(int retentionDays, boolean dryRun, int limit, LocalDateTime now) {
        PhotoPurgeOutcome total = PhotoPurgeOutcome.NONE;
        for (UUID bidId : events.findBidIdsWithLivePhotos()) {
            if (total.eligible() >= limit) break;
            if (!isDue(bidId, retentionDays, now)) continue;
            List<TrackingEventEntity> photos = events.findLivePhotosByBidId(bidId);
            List<TrackingEventEntity> gone = new ArrayList<>();
            int failed = 0;
            for (TrackingEventEntity e : photos) {
                if (dryRun) continue;
                if (deleteObject(e.getPhotoUrl())) {
                    gone.add(e);
                } else {
                    failed++;
                }
            }
            if (!gone.isEmpty()) {
                // Marquage échoué = lignes reprises au passage suivant (objet absent = supprimé).
                tx.executeWithoutResult(status -> {
                    for (TrackingEventEntity e : gone) {
                        e.markPhotoPurged(now);
                        events.save(e);
                    }
                    audit.log("bid", bidId, "TRACKING_PHOTOS_PURGED", null,
                            Map.of("count", gone.size(), "reason", "RETENTION_EXPIRED"));
                });
            }
            total = total.plus(new PhotoPurgeOutcome(photos.size(), gone.size(), failed));
        }
        return total;
    }

    private boolean isDue(UUID bidId, int retentionDays, LocalDateTime now) {
        Optional<BidEntity> bid = bids.findById(bidId);
        // Fermé par défaut : un bid soft-deleted (ou absent) peut porter un litige ou un
        // signalement ; ses photos restent référencées, le rapprochement ne les touche pas.
        if (bid.isEmpty()) return false;
        Optional<LocalDateTime> end = BidMediaRetention.end(bid.get(), retentionDays);
        if (end.isEmpty() || now.isBefore(end.get())) return false;
        return holds.stream().noneMatch(h -> h.holds(bidId, List.of()));
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
            log.warn("TrackingPhotoPurge: suppression R2 en échec pour {} : {}", key, e.toString());
            return false;
        }
    }
}

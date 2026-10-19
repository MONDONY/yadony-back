package com.yadony.api.payments.supplement;

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
 * Photo d'un supplément de prix : purgée N jours après la livraison du colis (ou sa fin), avec les
 * mêmes règles et retenues que les photos de suivi ({@code TrackingPhotoPurgeParticipant}). La clé
 * reste en base ({@code photo_key} est obligatoire), marquée {@code photo_purged_at} : la source de
 * références ne la revendique plus et l'API ne sert plus d'URL. Aucune transaction autour des appels
 * R2 : lectures en autocommit, marquage et audit en transaction courte.
 */
@Component
public class BidSupplementPhotoPurgeParticipant implements PhotoPurgeParticipant {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementPhotoPurgeParticipant.class);

    private final BidSupplementRepository supplements;
    private final BidRepository bids;
    private final StorageService storage;
    private final AuditService audit;
    private final List<MessagingMediaRetentionHold> holds;
    private final TransactionTemplate tx;

    public BidSupplementPhotoPurgeParticipant(BidSupplementRepository supplements, BidRepository bids,
                                              StorageService storage, AuditService audit,
                                              List<MessagingMediaRetentionHold> holds,
                                              PlatformTransactionManager transactionManager) {
        this.supplements = supplements;
        this.bids = bids;
        this.storage = storage;
        this.audit = audit;
        this.holds = holds;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "bid-supplement";
    }

    @Override
    public PhotoPurgeOutcome purgeDue(int retentionDays, boolean dryRun, int limit, LocalDateTime now) {
        PhotoPurgeOutcome total = PhotoPurgeOutcome.NONE;
        for (UUID bidId : supplements.findBidIdsWithLivePhotos()) {
            if (total.eligible() >= limit) break;
            if (!isDue(bidId, retentionDays, now)) continue;
            List<BidSupplementEntity> photos = supplements.findLivePhotosByBidId(bidId);
            List<BidSupplementEntity> gone = new ArrayList<>();
            int failed = 0;
            for (BidSupplementEntity s : photos) {
                if (dryRun) continue;
                if (deleteObject(s.getPhotoKey())) {
                    gone.add(s);
                } else {
                    failed++;
                }
            }
            if (!gone.isEmpty()) {
                // Marquage échoué = lignes reprises au passage suivant (objet absent = supprimé).
                tx.executeWithoutResult(status -> {
                    for (BidSupplementEntity s : gone) {
                        s.markPhotoPurged(now);
                        supplements.save(s);
                    }
                    audit.log("bid", bidId, "BID_SUPPLEMENT_PHOTOS_PURGED", null,
                            Map.of("count", gone.size(), "reason", "RETENTION_EXPIRED"));
                });
            }
            total = total.plus(new PhotoPurgeOutcome(photos.size(), gone.size(), failed));
        }
        return total;
    }

    private boolean isDue(UUID bidId, int retentionDays, LocalDateTime now) {
        Optional<BidEntity> bid = bids.findById(bidId);
        // Fermé par défaut, comme les photos de suivi : un colis supprimé ou absent garde ses photos.
        if (bid.isEmpty()) return false;
        Optional<LocalDateTime> end = BidMediaRetention.end(bid.get(), retentionDays);
        if (end.isEmpty() || now.isBefore(end.get())) return false;
        return holds.stream().noneMatch(h -> h.holds(bidId, List.of()));
    }

    /** Vrai si l'objet n'existe plus dans R2 (supprimé ou déjà absent). */
    private boolean deleteObject(String key) {
        try {
            storage.deleteFile(key);
            return true;
        } catch (NoSuchKeyException e) {
            return true;
        } catch (RuntimeException e) {
            log.warn("BidSupplementPhotoPurge: suppression R2 en échec pour {} : {}", key, e.toString());
            return false;
        }
    }
}

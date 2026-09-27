package com.yadony.api.matching;

import com.yadony.api.common.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Tous les jours à minuit : purge physiquement les photos DELETING (S3 + ligne) puis
 * balaye défensivement les photos ACTIVE dont le bid a atteint un état terminal/route
 * (rattrape un listener manqué et les bids de checkout abandonné, soft-deleted).
 */
@Component
public class BidPhotoCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BidPhotoCleanupScheduler.class);

    /** États du bid où les photos ne sont plus nécessaires et doivent être purgées. */
    private static final Set<BidStatus> DELETING_TRIGGER_STATES = Set.of(
            BidStatus.REJECTED, BidStatus.CANCELLED, BidStatus.EXPIRED,
            BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED,
            BidStatus.COMPLETED);

    private final BidPhotoRepository photoRepository;
    private final BidRepository bidRepository;
    private final StorageService storageService;

    public BidPhotoCleanupScheduler(BidPhotoRepository photoRepository,
                                    BidRepository bidRepository,
                                    StorageService storageService) {
        this.photoRepository = photoRepository;
        this.bidRepository = bidRepository;
        this.storageService = storageService;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @Transactional
    public void purgeDeletingPhotos() {
        defensiveSweep();

        List<BidPhotoEntity> toPurge = photoRepository.findByStatus(BidPhotoStatus.DELETING);
        for (BidPhotoEntity photo : toPurge) {
            try {
                storageService.deleteFile(photo.getObjectKey());
            } catch (Exception e) {
                log.warn("BidPhotoCleanup: S3 delete failed for {} (key={}): {} — removing row anyway",
                        photo.getId(), photo.getObjectKey(), e.getMessage());
            }
            photoRepository.delete(photo);
        }
        if (!toPurge.isEmpty()) {
            log.info("BidPhotoCleanup: purged {} photos", toPurge.size());
        }
    }

    /** Passe en DELETING les photos ACTIVE dont le bid est terminal/route ou absent. */
    private void defensiveSweep() {
        List<BidPhotoEntity> active = photoRepository.findByStatus(BidPhotoStatus.ACTIVE);
        for (BidPhotoEntity photo : active) {
            BidStatus status = bidRepository.findById(photo.getBidId())
                    .map(b -> b.getStatus())
                    .orElse(null);
            if (status == null || DELETING_TRIGGER_STATES.contains(status)) {
                photo.markDeleting();
                photoRepository.save(photo);
            }
        }
    }
}

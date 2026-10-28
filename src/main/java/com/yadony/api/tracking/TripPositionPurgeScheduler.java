package com.yadony.api.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet de sécurité quotidien : efface les positions des trajets clos (COMPLETED, CANCELLED,
 * REMOVED_BY_ADMIN) que l'événement aurait manquées, et toute position de plus de
 * {@link TripPositionPurgeService#MAX_SAMPLE_AGE_DAYS} jours. Idempotent.
 */
@Component
public class TripPositionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripPositionPurgeScheduler.class);

    private final TripPositionPurgeService purgeService;

    public TripPositionPurgeScheduler(TripPositionPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    // Tous les jours à 03:30 UTC.
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void purgeStalePositions() {
        try {
            purgeService.purgeClosedAndExpired();
        } catch (RuntimeException e) {
            log.error("Position safety-net purge failed", e);
        }
    }
}

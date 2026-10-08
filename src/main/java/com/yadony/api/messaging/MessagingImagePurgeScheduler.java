package com.yadony.api.messaging;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purge horaire des photos de messagerie échues (FLUTTER-B4). Idempotente : une photo
 * purgée porte {@code purged_at} et sort de la sélection ; deux instances concurrentes ne
 * feraient que tenter deux fois la même suppression R2, sans effet.
 */
@Component
public class MessagingImagePurgeScheduler {

    private final MessagingImageRetentionService retentionService;

    public MessagingImagePurgeScheduler(MessagingImageRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${yadony.messaging.images.purge-cron:0 20 * * * *}", zone = "UTC")
    public void purgeExpiredImages() {
        retentionService.purgeExpired();
    }
}

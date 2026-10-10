package com.yadony.api.common.media;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rapprochement R2 ↔ base, une fois par jour (la purge horaire tourne à la minute 40). */
@Component
public class PhotoReconciliationScheduler {

    private final PhotoReconciliationService service;

    public PhotoReconciliationScheduler(PhotoReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${yadony.photos.reconcile-cron:0 10 4 * * *}", zone = "UTC")
    public void daily() {
        service.reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE);
    }
}

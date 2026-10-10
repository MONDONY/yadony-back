package com.yadony.api.common.media;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Toutes les heures à la minute 40 (le chat purge à la minute 20). */
@Component
public class PhotoPurgeScheduler {

    private final PhotoPurgeRunner runner;

    public PhotoPurgeScheduler(PhotoPurgeRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${yadony.photos.purge-cron:0 40 * * * *}", zone = "UTC")
    public void hourly() {
        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);
    }
}

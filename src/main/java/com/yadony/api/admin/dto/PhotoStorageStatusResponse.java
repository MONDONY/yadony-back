package com.yadony.api.admin.dto;

import com.yadony.api.common.media.PhotoPurgeRunEntity;

import java.time.LocalDateTime;
import java.util.List;

/** Etat de la carte « Stockage photos » du panel admin. */
public record PhotoStorageStatusResponse(
        boolean purgeEnabled,
        int retentionDays,
        boolean running,
        List<RunView> lastPurges,
        List<RunView> lastReconciles,
        long uploads24h, long bytes24h,
        long uploads7d, long bytes7d) {

    public record RunView(String kind, boolean dryRun, String triggerSource, LocalDateTime startedAt,
                          LocalDateTime finishedAt, int eligible, int deleted, int failed, String error) {
        public static RunView of(PhotoPurgeRunEntity r) {
            return new RunView(r.getKind(), r.isDryRun(), r.getTriggerSource(), r.getStartedAt(),
                    r.getFinishedAt(), r.getEligible(), r.getDeleted(), r.getFailed(), r.getError());
        }
    }
}

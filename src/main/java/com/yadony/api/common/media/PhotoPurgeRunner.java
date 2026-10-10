package com.yadony.api.common.media;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passage de purge après livraison. Un seul passage à la fois (purge et rapprochement
 * partagent le verrou), journalisé dans photo_purge_runs. L'échec d'un participant
 * n'arrête pas les autres ; le passage suivant reprend ce qui reste.
 */
@Service
public class PhotoPurgeRunner {

    private static final Logger log = LoggerFactory.getLogger(PhotoPurgeRunner.class);

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_ADMIN = "ADMIN";
    public static final int BATCH_LIMIT = 500;
    static final int FAILING_RUNS_BEFORE_ALERT = 5;
    static final long STALE_HOURS = 48;

    private final List<PhotoPurgeParticipant> participants;
    private final PlatformSettingsService settings;
    private final PhotoPurgeRunRepository runs;
    private final AdminAlertService alerts;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public PhotoPurgeRunner(List<PhotoPurgeParticipant> participants, PlatformSettingsService settings,
                            PhotoPurgeRunRepository runs, AdminAlertService alerts) {
        this(participants, settings, runs, alerts, Clock.systemUTC());
    }

    PhotoPurgeRunner(List<PhotoPurgeParticipant> participants, PlatformSettingsService settings,
                     PhotoPurgeRunRepository runs, AdminAlertService alerts, Clock clock) {
        this.participants = participants;
        this.settings = settings;
        this.runs = runs;
        this.alerts = alerts;
        this.clock = clock;
    }

    public boolean tryLock() { return running.compareAndSet(false, true); }
    public void unlock() { running.set(false); }
    public boolean isRunning() { return running.get(); }

    public Optional<PhotoPurgeRunEntity> runPurge(String triggerSource) {
        if (!tryLock()) {
            log.info("PhotoPurge: passage déjà en cours, {} ignoré", triggerSource);
            return Optional.empty();
        }
        try {
            warnIfStale();
            LocalDateTime now = now();
            boolean dryRun = !settings.photoPurgeEnabled();
            int days = settings.photoRetentionDays();
            PhotoPurgeRunEntity run = runs.save(new PhotoPurgeRunEntity(
                    PhotoPurgeRunEntity.KIND_PURGE, dryRun, triggerSource, now));
            PhotoPurgeOutcome total = PhotoPurgeOutcome.NONE;
            for (PhotoPurgeParticipant p : participants) {
                try {
                    PhotoPurgeOutcome o = p.purgeDue(days, dryRun, BATCH_LIMIT, now);
                    log.info("PhotoPurge[{}]{}: éligibles={} supprimées={} échecs={}",
                            p.name(), dryRun ? " (à blanc)" : "", o.eligible(), o.deleted(), o.failed());
                    total = total.plus(o);
                } catch (RuntimeException e) {
                    log.warn("PhotoPurge[{}] en échec : {}", p.name(), e.toString());
                    total = total.plus(new PhotoPurgeOutcome(0, 0, 1));
                }
            }
            run.finish(total, now());
            runs.save(run);
            alertIfFailing();
            return Optional.of(run);
        } finally {
            unlock();
        }
    }

    private void alertIfFailing() {
        List<PhotoPurgeRunEntity> last = runs.findTop5ByKindOrderByStartedAtDesc(PhotoPurgeRunEntity.KIND_PURGE);
        if (last.size() >= FAILING_RUNS_BEFORE_ALERT && last.stream().allMatch(r -> r.getFailed() > 0)) {
            alerts.raise("PHOTO_PURGE_FAILING",
                    "La purge des photos échoue depuis " + FAILING_RUNS_BEFORE_ALERT + " passages",
                    Map.of("lastFailed", last.get(0).getFailed()));
        }
    }

    private void warnIfStale() {
        Optional<PhotoPurgeRunEntity> last = runs
                .findTopByKindAndErrorIsNullAndFinishedAtIsNotNullOrderByStartedAtDesc(PhotoPurgeRunEntity.KIND_PURGE);
        if (last.isPresent() && last.get().getFinishedAt().isBefore(now().minusHours(STALE_HOURS))) {
            alerts.raise("PHOTO_PURGE_STALE",
                    "Aucune purge des photos réussie depuis plus de " + STALE_HOURS + " h",
                    Map.of("lastSuccess", String.valueOf(last.get().getFinishedAt())));
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}

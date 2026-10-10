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
    static final long ALERT_COOLDOWN_HOURS = 24;

    private final List<PhotoPurgeParticipant> participants;
    private final PlatformSettingsService settings;
    private final PhotoPurgeRunRepository runs;
    private final AdminAlertService alerts;
    private final Clock clock;
    private final Map<String, LocalDateTime> lastAlertAt = new java.util.concurrent.ConcurrentHashMap<>();
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
            LocalDateTime now = now();
            boolean dryRun = !settings.photoPurgeEnabled();
            PhotoPurgeRunEntity run = runs.save(new PhotoPurgeRunEntity(
                    PhotoPurgeRunEntity.KIND_PURGE, dryRun, triggerSource, now));
            try {
                int days = settings.photoRetentionDays();
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
            } catch (RuntimeException e) {
                log.error("PhotoPurge: passage en erreur : {}", e.toString());
                run.fail(e.toString(), now());
                try {
                    runs.save(run);
                } catch (RuntimeException saveError) {
                    log.error("PhotoPurge: journal du passage non enregistré : {}", saveError.toString());
                }
                throw e;
            }
            // Après le passage : l'alerte des 48 h mesure que la purge TOURNE (passage terminé
            // sans exception, échecs partiels compris). Les échecs partiels relèvent de
            // PHOTO_PURGE_FAILING. Une base neuve sans passage terminé ne lève rien : au
            // déploiement, la purge tourne à blanc dès la première heure.
            // Hors du try du passage : une alerte en échec ne doit pas marquer le passage en erreur.
            try {
                alertIfFailing();
            } catch (RuntimeException e) {
                log.warn("PhotoPurge: contrôle des échecs en erreur : {}", e.toString());
            }
            try {
                warnIfStale();
            } catch (RuntimeException e) {
                log.warn("PhotoPurge: contrôle de fraîcheur en erreur : {}", e.toString());
            }
            return Optional.of(run);
        } finally {
            unlock();
        }
    }

    /** Une alerte par code au plus toutes les 24 h (AdminAlertService ne dédoublonne pas). */
    private void raiseThrottled(String code, String detail, Map<String, Object> context) {
        LocalDateTime now = now();
        LocalDateTime last = lastAlertAt.get(code);
        if (last != null && last.isAfter(now.minusHours(ALERT_COOLDOWN_HOURS))) {
            return;
        }
        alerts.raise(code, detail, context);
        lastAlertAt.put(code, now);
    }

    private void alertIfFailing() {
        List<PhotoPurgeRunEntity> last = runs.findTop5ByKindOrderByStartedAtDesc(PhotoPurgeRunEntity.KIND_PURGE);
        if (last.size() >= FAILING_RUNS_BEFORE_ALERT && last.stream().allMatch(r -> r.getFailed() > 0)) {
            raiseThrottled("PHOTO_PURGE_FAILING",
                    "La purge des photos échoue depuis " + FAILING_RUNS_BEFORE_ALERT + " passages",
                    Map.of("lastFailed", last.get(0).getFailed()));
        }
    }

    private void warnIfStale() {
        Optional<PhotoPurgeRunEntity> last = runs
                .findTopByKindAndErrorIsNullAndFinishedAtIsNotNullOrderByStartedAtDesc(PhotoPurgeRunEntity.KIND_PURGE);
        if (last.isPresent() && last.get().getFinishedAt().isBefore(now().minusHours(STALE_HOURS))) {
            raiseThrottled("PHOTO_PURGE_STALE",
                    "Aucune purge des photos réussie depuis plus de " + STALE_HOURS + " h",
                    Map.of("lastSuccess", String.valueOf(last.get().getFinishedAt())));
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}

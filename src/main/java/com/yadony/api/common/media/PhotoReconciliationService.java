package com.yadony.api.common.media;

import com.yadony.api.common.StorageService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rapprochement quotidien R2 ↔ base : supprime les objets de plus de {@link #ORPHAN_AGE}
 * qu'aucune table ne référence (envois abandonnés, historique, fuites). Opération sans
 * retour : un préfixe n'est traité que si TOUTES ses sources ont répondu, et la garde de
 * volume suspend un préfixe dont la majorité des objets paraît orpheline (source cassée).
 * Partage le verrou de la purge et l'interrupteur photo_purge_enabled (à blanc sinon).
 */
@Service
public class PhotoReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PhotoReconciliationService.class);

    /**
     * Préfixes rapprochés. {@code messaging/} en est EXCLU : les pièces de l'ancien
     * POST /upload du chat ne sont référencées que dans les messages Firestore, pas en base ;
     * le chat a sa propre purge (MessagingImageRetentionService, deleteByPrefix à la
     * suppression de la conversation). {@code kyc/} n'est plus un préfixe de StorageService.
     */
    public static final List<String> RECONCILED_PREFIXES = List.of(
            "package_requests/", "bids/", "tracking/", "users/", "reports/", "support/", "requests/");
    /** Préfixes morts : plus rien ne les référence, tout ce qui y traîne est à supprimer. */
    public static final Set<String> PREFIXES_WITHOUT_SOURCE = Set.of("requests/");
    /**
     * Âge minimal d'un objet avant d'être jugé orphelin. Un envoi rattaché en base plus de 24 h
     * après son upload (ex. scan hors ligne synchronisé tard) serait supprimé : 24 h est le
     * minimum, ne JAMAIS réduire (formulaire de demande : un brouillon est enregistré en base).
     */
    public static final Duration ORPHAN_AGE = Duration.ofHours(24);
    public static final int MAX_DELETES_PER_RUN = 5000;
    /**
     * Plafond de clés listées par préfixe : le listing compte aussi les clés vivantes, le
     * borner à MAX_DELETES_PER_RUN figerait le rapprochement sur les premières clés d'un
     * préfixe qui a plus de 5000 photos vivantes de plus de 24 h.
     */
    public static final int MAX_LISTED_PER_PREFIX = 100_000;
    /** Garde de volume : au-delà de ce nombre d'orphelins ET de cette part, le préfixe est suspendu. */
    static final int GUARD_MIN_ORPHANS = 100;
    static final double GUARD_MAX_ORPHAN_SHARE = 0.5;
    /** Rapprochement bloqué (garde de volume, préfixe sans source dédiée, passage en erreur). */
    public static final String ALERT_BLOCKED = "PHOTO_RECONCILE_BLOCKED";
    /** Une alerte au plus toutes les 24 h, comme celles de la purge. */
    static final long ALERT_COOLDOWN_HOURS = 24;

    private final StorageService storage;
    private final List<MediaKeyReferenceSource> sources;
    private final PlatformSettingsService settings;
    private final PhotoPurgeRunRepository runs;
    private final PhotoPurgeRunner runner;
    private final AdminAlertService alerts;
    private final Clock clock;
    private final List<String> prefixes;
    private volatile LocalDateTime lastBlockedAlertAt;

    @Autowired
    public PhotoReconciliationService(StorageService storage, List<MediaKeyReferenceSource> sources,
                                      PlatformSettingsService settings, PhotoPurgeRunRepository runs,
                                      PhotoPurgeRunner runner, AdminAlertService alerts) {
        this(storage, sources, settings, runs, runner, alerts, Clock.systemUTC(), RECONCILED_PREFIXES);
    }

    PhotoReconciliationService(StorageService storage, List<MediaKeyReferenceSource> sources,
                               PlatformSettingsService settings, PhotoPurgeRunRepository runs,
                               PhotoPurgeRunner runner, AdminAlertService alerts, Clock clock,
                               List<String> prefixes) {
        this.storage = storage;
        this.sources = sources;
        this.settings = settings;
        this.runs = runs;
        this.runner = runner;
        this.alerts = alerts;
        this.clock = clock;
        this.prefixes = prefixes;
    }

    /** Vide si un passage (purge ou rapprochement) est déjà en cours. */
    public Optional<PhotoPurgeRunEntity> reconcile(String triggerSource) {
        return reconcile(triggerSource, false);
    }

    /**
     * {@code allowBulk} lève ponctuellement la garde de volume pour ce passage (premier passage
     * réel : l'historique d'avant chantier dépasse 50 % d'orphelins). Réservé à l'admin, le
     * scheduler ne le passe jamais ; le reste (interrupteur, sources dédiées, budget, âge) est inchangé.
     */
    public Optional<PhotoPurgeRunEntity> reconcile(String triggerSource, boolean allowBulk) {
        if (!runner.tryLock()) {
            log.info("PhotoReconciliation: passage déjà en cours, {} ignoré", triggerSource);
            return Optional.empty();
        }
        List<String> blocked = new ArrayList<>();
        boolean dryRun = false;
        try {
            dryRun = !settings.photoPurgeEnabled();
            PhotoPurgeRunEntity run = runs.save(new PhotoPurgeRunEntity(
                    PhotoPurgeRunEntity.KIND_RECONCILE, dryRun, triggerSource, now()));
            try {
                List<String> uncovered = uncoveredPrefixes();
                if (!uncovered.isEmpty()) {
                    log.error("PhotoReconciliation: préfixes sans source dédiée {}, rien n'est supprimé",
                            uncovered);
                    run.fail("Préfixes sans source de références dédiée : " + uncovered, now());
                    blocked.add("préfixes sans source de références dédiée " + uncovered);
                    runs.save(run);
                    return Optional.of(run);
                }
                Instant cutoff = clock.instant().minus(ORPHAN_AGE);
                PhotoPurgeOutcome total = PhotoPurgeOutcome.NONE;
                for (String prefix : prefixes) {
                    // À blanc, pas de budget : le décompte sert à décider d'allumer l'interrupteur,
                    // il doit être complet. En réel, au plus MAX_DELETES_PER_RUN suppressions.
                    int budget = dryRun ? Integer.MAX_VALUE : MAX_DELETES_PER_RUN - total.eligible();
                    if (budget <= 0) break;
                    total = total.plus(reconcilePrefix(prefix, cutoff, budget, dryRun, allowBulk, blocked));
                }
                run.finish(total, now());
                runs.save(run);
                log.info("PhotoReconciliation{}: orphelins={} supprimés={} échecs={}",
                        dryRun ? " (à blanc)" : "", total.eligible(), total.deleted(), total.failed());
            } catch (RuntimeException e) {
                log.error("PhotoReconciliation: passage en erreur : {}", e.toString());
                run.fail(e.toString(), now());
                blocked.add("passage en erreur : " + e);
                try {
                    runs.save(run);
                } catch (RuntimeException saveError) {
                    log.error("PhotoReconciliation: journal du passage non enregistré : {}", saveError.toString());
                }
            }
            return Optional.of(run);
        } finally {
            runner.unlock();
            // Hors du passage : une alerte en échec ne doit ni marquer le passage en erreur
            // ni empêcher la libération du verrou.
            alertIfBlocked(blocked, dryRun, triggerSource);
        }
    }

    /**
     * Rapprochement bloqué : rien n'est supprimé sur le préfixe concerné tant qu'un humain
     * n'a pas regardé. Alerte aussi à blanc (le décompte à blanc doit signaler qu'un préfixe
     * serait bloqué une fois l'interrupteur allumé), une fois par 24 h au plus.
     */
    private void alertIfBlocked(List<String> blocked, boolean dryRun, String triggerSource) {
        if (blocked.isEmpty()) return;
        try {
            LocalDateTime now = now();
            LocalDateTime last = lastBlockedAlertAt;
            if (last != null && last.isAfter(now.minusHours(ALERT_COOLDOWN_HOURS))) {
                return;
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("dryRun", dryRun);
            context.put("trigger", triggerSource);
            context.put("reasons", List.copyOf(blocked));
            alerts.raise(ALERT_BLOCKED, "Rapprochement des photos bloqué" + (dryRun ? " (à blanc)" : "")
                    + " : " + String.join(" ; ", blocked), context);
            lastBlockedAlertAt = now;
        } catch (RuntimeException e) {
            log.error("PhotoReconciliation: alerte de blocage non envoyée : {}", e.toString());
        }
    }

    private List<String> uncoveredPrefixes() {
        return prefixes.stream()
                .filter(p -> !PREFIXES_WITHOUT_SOURCE.contains(p))
                .filter(p -> sources.stream().noneMatch(s -> s.dedicated() && s.prefixes().contains(p)))
                .toList();
    }

    private PhotoPurgeOutcome reconcilePrefix(String prefix, Instant cutoff, int budget, boolean dryRun,
                                           boolean allowBulk, List<String> blocked) {
        Set<String> live;
        List<String> listed;
        try {
            // Références lues AVANT le listing : une clé envoyée pendant le passage a moins
            // de 24 h et n'est pas listée.
            live = referencedKeys(prefix);
            listed = storage.listObjectsOlderThan(prefix, cutoff, MAX_LISTED_PER_PREFIX);
        } catch (RuntimeException e) {
            log.error("PhotoReconciliation[{}]: références ou listing illisibles, préfixe ignoré : {}",
                    prefix, e.toString());
            return new PhotoPurgeOutcome(0, 0, 1);
        }
        if (listed.size() >= MAX_LISTED_PER_PREFIX) {
            log.warn("PhotoReconciliation[{}]: listing tronqué à {} clés", prefix, MAX_LISTED_PER_PREFIX);
        }
        List<String> orphans = listed.stream().filter(k -> !live.contains(k)).toList();
        if (guardTripped(prefix, orphans.size(), listed.size())) {
            if (allowBulk) {
                log.warn("PhotoReconciliation[{}]{}: garde de volume levée par l'admin, {} orphelins sur {} "
                                + "objets listés traités malgré tout",
                        prefix, dryRun ? " (à blanc)" : "", orphans.size(), listed.size());
            } else {
                log.error("PhotoReconciliation[{}]{}: {} orphelins sur {} objets listés (plus de {} %), "
                                + "préfixe suspendu : vérifier ses sources de références avant tout passage",
                        prefix, dryRun ? " (à blanc)" : "", orphans.size(), listed.size(),
                        (int) (GUARD_MAX_ORPHAN_SHARE * 100));
                blocked.add("garde de volume sur " + prefix + " (" + orphans.size() + " orphelins sur "
                        + listed.size() + " objets listés)");
                return new PhotoPurgeOutcome(0, 0, 1);
            }
        }
        int eligible = 0;
        int deleted = 0;
        int failed = 0;
        for (String key : orphans) {
            if (eligible >= budget) break;
            eligible++;
            if (dryRun) continue;
            try {
                storage.deleteFile(key);
                deleted++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("PhotoReconciliation: suppression en échec {} : {}", key, e.toString());
            }
        }
        if (eligible > 0) {
            log.info("PhotoReconciliation[{}]{}: listés={} orphelins={} supprimés={} échecs={}",
                    prefix, dryRun ? " (à blanc)" : "", listed.size(), eligible, deleted, failed);
        }
        return new PhotoPurgeOutcome(eligible, deleted, failed);
    }

    private Set<String> referencedKeys(String prefix) {
        Set<String> live = new HashSet<>();
        for (MediaKeyReferenceSource s : sources) {
            if (s.prefixes().contains(prefix)) live.addAll(s.referencedKeys(prefix));
        }
        return live;
    }

    /**
     * Garde de volume. Exemption voulue pour les préfixes morts ({@link #PREFIXES_WITHOUT_SOURCE},
     * {@code requests/}) : rien ne les référence, ils sont orphelins à 100 % par nature, la garde
     * les bloquerait à jamais et ils n'ont aucune photo vivante à protéger.
     */
    private static boolean guardTripped(String prefix, int orphans, int listed) {
        if (PREFIXES_WITHOUT_SOURCE.contains(prefix)) return false;
        return orphans > GUARD_MIN_ORPHANS && orphans > listed * GUARD_MAX_ORPHAN_SHARE;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}

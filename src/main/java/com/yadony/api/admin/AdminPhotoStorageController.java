package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.PhotoStorageStatusResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.media.MediaUploadRepository;
import com.yadony.api.common.media.PhotoPurgeRunEntity;
import com.yadony.api.common.media.PhotoPurgeRunRepository;
import com.yadony.api.common.media.PhotoPurgeRunner;
import com.yadony.api.common.media.PhotoReconciliationService;
import com.yadony.api.common.media.UploadStats;
import com.yadony.api.config.PlatformSettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Carte « Stockage photos » du panel : état de la purge et lancement manuel (rattrapage).
 *
 * <p>Chaque méthode re-déclare l'expression COMPLÈTE : une {@code @PreAuthorize} de méthode
 * remplace celle de la classe.
 */
@RestController
@RequestMapping("/admin/storage/photo-purge")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
public class AdminPhotoStorageController {

    private static final Logger log = LoggerFactory.getLogger(AdminPhotoStorageController.class);

    private final PhotoPurgeRunner runner;
    private final PhotoReconciliationService reconciliation;
    private final PhotoPurgeRunRepository runs;
    private final MediaUploadRepository uploads;
    private final PlatformSettingsService settings;
    private final AuditService audit;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "photo-purge-admin");
        t.setDaemon(true);
        return t;
    });

    public AdminPhotoStorageController(PhotoPurgeRunner runner, PhotoReconciliationService reconciliation,
                                       PhotoPurgeRunRepository runs, MediaUploadRepository uploads,
                                       PlatformSettingsService settings, AuditService audit) {
        this.runner = runner;
        this.reconciliation = reconciliation;
        this.runs = runs;
        this.uploads = uploads;
        this.settings = settings;
        this.audit = audit;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @GetMapping("/status")
    public PhotoStorageStatusResponse status() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UploadStats day = uploads.statsSince(now.minusHours(24));
        UploadStats week = uploads.statsSince(now.minusDays(7));
        return new PhotoStorageStatusResponse(
                settings.photoPurgeEnabled(), settings.photoRetentionDays(), runner.isRunning(),
                runs.findTop5ByKindOrderByStartedAtDesc(PhotoPurgeRunEntity.KIND_PURGE).stream()
                        .map(PhotoStorageStatusResponse.RunView::of).toList(),
                runs.findTop5ByKindOrderByStartedAtDesc(PhotoPurgeRunEntity.KIND_RECONCILE).stream()
                        .map(PhotoStorageStatusResponse.RunView::of).toList(),
                day.count(), day.bytes(), week.count(), week.bytes());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PostMapping("/run")
    public ResponseEntity<Map<String, Boolean>> run(Authentication authentication) {
        return launch("PHOTO_PURGE_TRIGGERED", authentication, false,
                () -> runner.runPurge(PhotoPurgeRunner.TRIGGER_ADMIN));
    }

    /**
     * {@code allowBulk=true} lève la garde de volume pour ce seul passage (premier passage réel
     * sur l'historique d'avant chantier).
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PostMapping("/reconcile")
    public ResponseEntity<Map<String, Boolean>> reconcile(
            @RequestParam(defaultValue = "false") boolean allowBulk, Authentication authentication) {
        return launch("PHOTO_RECONCILE_TRIGGERED", authentication, allowBulk,
                () -> reconciliation.reconcile(PhotoPurgeRunner.TRIGGER_ADMIN, allowBulk));
    }

    private ResponseEntity<Map<String, Boolean>> launch(String action, Authentication authentication,
                                                         boolean allowBulk, Runnable job) {
        if (runner.isRunning()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "photo-purge-running", "Conflict",
                    "Une purge est déjà en cours, réessayez dans quelques minutes.");
        }
        audit.log("photo_storage", null, action, adminId(authentication),
                Map.of("purgeEnabled", settings.photoPurgeEnabled(), "allowBulk", allowBulk));
        executor.submit(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.error("{} : exécution en erreur : {}", action, e.toString(), e);
            }
        });
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}

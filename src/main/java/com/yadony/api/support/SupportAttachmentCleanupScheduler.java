package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Une image uploadee puis abandonnee (l'utilisateur ferme l'app avant d'envoyer)
 * resterait sur R2 sans ligne d'attachement. Purge quotidienne, idempotente.
 */
@Component
public class SupportAttachmentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupportAttachmentCleanupScheduler.class);

    private static final String PREFIX = "support/";
    /** Large : un envoi lent ne doit jamais voir son image disparaitre sous lui. */
    private static final Duration GRACE_PERIOD = Duration.ofHours(24);

    private final StorageService storageService;
    private final SupportMessageAttachmentRepository attachmentRepository;

    public SupportAttachmentCleanupScheduler(StorageService storageService,
                                             SupportMessageAttachmentRepository attachmentRepository) {
        this.storageService = storageService;
        this.attachmentRepository = attachmentRepository;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void purgeOrphanAttachments() {
        List<String> candidates = storageService.listKeysOlderThan(
                PREFIX, Instant.now().minus(GRACE_PERIOD));
        if (candidates.isEmpty()) {
            return;
        }
        // Hypothese : SupportMessageAttachmentEntity n'a aucun soft-delete operationnel.
        // Si une future evolution en introduit un, cette requete (filtree par @Where
        // deleted_at IS NULL) rendrait la cle invisible et le scheduler supprimerait
        // un objet S3 encore reference : il faudrait alors une requete native.
        Set<String> referenced = Set.copyOf(attachmentRepository.findObjectKeysByObjectKeyIn(candidates));

        int purged = 0;
        for (String key : candidates) {
            if (referenced.contains(key)) {
                continue;
            }
            try {
                storageService.deleteFile(key);
                purged++;
            } catch (RuntimeException e) {
                log.warn("SupportAttachmentCleanup: suppression impossible pour {} : {}", key, e.getMessage());
            }
        }
        if (purged > 0) {
            log.info("SupportAttachmentCleanup: {} pieces jointes orphelines purgees", purged);
        }
    }
}

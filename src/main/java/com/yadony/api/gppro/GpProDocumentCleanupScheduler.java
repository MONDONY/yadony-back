package com.yadony.api.gppro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Purge de secours des justificatifs : téléversements jamais rattachés (plus de 24 h) et
 * justificatifs d'une demande décidée dont la purge à la décision a échoué.
 */
@Component
public class GpProDocumentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(GpProDocumentCleanupScheduler.class);
    static final int ORPHAN_AGE_HOURS = 24;

    private final GpProDocumentRepository documents;
    private final GpProDocumentService documentService;
    private final GpProProperties properties;

    public GpProDocumentCleanupScheduler(GpProDocumentRepository documents,
                                         GpProDocumentService documentService, GpProProperties properties) {
        this.documents = documents;
        this.documentService = documentService;
        this.properties = properties;
    }

    @Scheduled(cron = "${yadony.gp-pro.cleanup-cron:0 45 4 * * *}", zone = "UTC")
    public void run() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        List<GpProDocumentEntity> candidates = new ArrayList<>(documents.findOrphansCreatedBefore(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(ORPHAN_AGE_HOURS)));
        candidates.addAll(documents.findUnpurgedOfDecidedApplications());
        int purged = 0;
        for (GpProDocumentEntity doc : candidates) {
            if (documentService.purge(doc)) {
                purged++;
            }
        }
        if (!candidates.isEmpty()) {
            log.info("GP Pro document cleanup: {} purged out of {} candidates", purged, candidates.size());
        }
    }
}

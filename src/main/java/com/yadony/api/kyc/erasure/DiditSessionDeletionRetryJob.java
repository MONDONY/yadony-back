package com.yadony.api.kyc.erasure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rattrapage des effacements Didit en échec (Didit injoignable, 429, 503, clé sans la permission
 * {@code delete:sessions}) ou jamais tentés (serveur arrêté entre le commit et l'essai).
 */
@Component
public class DiditSessionDeletionRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DiditSessionDeletionRetryJob.class);

    private final DiditSessionErasureService erasureService;
    private final boolean enabled;

    public DiditSessionDeletionRetryJob(DiditSessionErasureService erasureService,
                                        @Value("${yadony.kyc.didit-erasure.enabled:true}") boolean enabled) {
        this.erasureService = erasureService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${yadony.kyc.didit-erasure.interval:PT15M}",
            initialDelayString = "${yadony.kyc.didit-erasure.initial-delay:PT3M}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        int attempted = erasureService.attemptDue();
        if (attempted > 0) {
            log.info("Effacements Didit repris : {}", attempted);
        }
    }
}

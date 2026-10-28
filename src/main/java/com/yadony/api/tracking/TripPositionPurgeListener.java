package com.yadony.api.tracking;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.matching.events.AnnouncementCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Branche l'effacement des positions floues sur deux événements d'autres packages.
 *
 * <p>Un échec ici ne remonte JAMAIS : il ne casse ni la clôture du trajet ni la suppression du
 * compte, le filet quotidien rattrape. Le try/catch est volontairement hors de la transaction
 * REQUIRES_NEW du service, pour absorber aussi un rollback-only.
 */
@Component
public class TripPositionPurgeListener {

    private static final Logger log = LoggerFactory.getLogger(TripPositionPurgeListener.class);

    private final TripPositionPurgeService purgeService;

    public TripPositionPurgeListener(TripPositionPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnnouncementCompleted(AnnouncementCompletedEvent event) {
        try {
            purgeService.purgeTrip(event.announcementId());
        } catch (RuntimeException e) {
            log.warn("Position purge failed for announcementId={} (safety net will retry): {}",
                    event.announcementId(), e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    public void onAccountDeletion(AccountDeletionRequestedEvent event) {
        try {
            purgeService.purgeTraveler(event.getUserId());
        } catch (RuntimeException e) {
            log.warn("Position purge failed for deleted userId={} (safety net will retry): {}",
                    event.getUserId(), e.getClass().getSimpleName());
        }
    }
}

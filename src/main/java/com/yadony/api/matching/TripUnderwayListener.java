package com.yadony.api.matching;

import com.yadony.api.tracking.events.ParcelInTransitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Un colis passé en transit signifie que le trajet est parti : il quitte le
 * marché, même avant son heure de départ prévue (FLUTTER-AE). Les demandes
 * encore en attente expirent et sont remboursées, comme au départ horaire.
 */
@Component
public class TripUnderwayListener {

    private static final Logger log = LoggerFactory.getLogger(TripUnderwayListener.class);

    private final AnnouncementService announcementService;

    public TripUnderwayListener(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onParcelInTransit(ParcelInTransitEvent event) {
        try {
            announcementService.markUnderway(event.announcementId(), "PARCEL_IN_TRANSIT");
        } catch (RuntimeException e) {
            // Le scan est déjà enregistré : le départ horaire rattrapera la bascule.
            log.error("Trip underway transition failed for announcement {}: {}",
                    event.announcementId(), e.getMessage(), e);
        }
    }
}

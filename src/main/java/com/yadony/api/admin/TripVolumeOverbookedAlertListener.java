package com.yadony.api.admin;

import com.yadony.api.matching.events.TripVolumeOverbookedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surréservation d'un trajet vendu au m³ (fil déjà payé matérialisé sans assez de volume) : alerte
 * {@code VOLUME_OVERBOOKED_<trajet>}, une ouverte par trajet, après le commit de la matérialisation. L'équipe arbitre avec
 * le transporteur (refus au dépôt, autre trajet) ; aucun argent ne bouge automatiquement.
 */
@Component
public class TripVolumeOverbookedAlertListener {

    private static final Logger log = LoggerFactory.getLogger(TripVolumeOverbookedAlertListener.class);

    /** Préfixe (18) + UUID du trajet (36) = 54, sous la limite de 60 de {@code admin_alerts.type}. */
    public static final String ALERT_PREFIX = "VOLUME_OVERBOOKED_";

    private final AdminAlertEscalator alerts;

    public TripVolumeOverbookedAlertListener(AdminAlertEscalator alerts) {
        this.alerts = alerts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOverbooked(TripVolumeOverbookedEvent event) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("announcementId", event.announcementId().toString());
            context.put("bidId", String.valueOf(event.bidId()));
            context.put("volumeM3", String.valueOf(event.volumeM3()));
            context.put("takenM3", String.valueOf(event.takenM3()));
            alerts.raiseOnce(ALERT_PREFIX + event.announcementId(),
                    "Trajet " + event.announcementId() + " surréservé : le colis " + event.bidId() + " (" + event.volumeM3()
                            + " m³, payé) n'a trouvé que " + event.takenM3() + " m³ libres. À arbitrer avec le transporteur.",
                    context);
        } catch (RuntimeException e) {
            log.error("Alerte {}{} non levée", ALERT_PREFIX, event.announcementId(), e);
        }
    }
}

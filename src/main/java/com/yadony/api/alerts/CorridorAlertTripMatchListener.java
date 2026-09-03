package com.yadony.api.alerts;

import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Notification TEMPS RÉEL des alertes trajet de l'expéditeur.
 *
 * <p>À la création d'un trajet ({@link AnnouncementCreatedEvent}, après commit),
 * trouve les alertes SENDER_WANTS_TRIPS actives qui matchent (corridor + dates +
 * zone de remise) et notifie immédiatement leur propriétaire. Un cooldown par
 * alerte ({@link #COOLDOWN}) évite les rafales si plusieurs trajets matchent en
 * quelques minutes. Le digest quotidien reste en filet de sécurité (et sert le
 * côté colis), {@code lastNotifiedAt} étant partagé pour éviter les doublons.
 */
@Component
public class CorridorAlertTripMatchListener {

    private static final Logger log =
            LoggerFactory.getLogger(CorridorAlertTripMatchListener.class);

    /** Anti-rafale : pas plus d'une notif par alerte sur cette fenêtre. */
    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    private final AnnouncementRepository announcementRepository;
    private final AlertService alertService;
    private final CorridorAlertRepository alertRepository;
    private final NotificationDispatcher notificationDispatcher;

    public CorridorAlertTripMatchListener(AnnouncementRepository announcementRepository,
                                          AlertService alertService,
                                          CorridorAlertRepository alertRepository,
                                          NotificationDispatcher notificationDispatcher) {
        this.announcementRepository = announcementRepository;
        this.alertService = alertService;
        this.alertRepository = alertRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAnnouncementCreated(AnnouncementCreatedEvent event) {
        AnnouncementEntity trip = announcementRepository.findById(event.announcementId()).orElse(null);
        if (trip == null) {
            return;
        }
        List<CorridorAlertEntity> matches = alertService.findSenderAlertsMatchingTrip(trip);
        if (matches.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String corridor = MatchingTextUtil.corridorLabel(trip.getDepartureCity(), trip.getArrivalCity());

        for (CorridorAlertEntity alert : matches) {
            try {
                if (alert.getLastNotifiedAt() != null
                        && Duration.between(alert.getLastNotifiedAt(), now).compareTo(COOLDOWN) < 0) {
                    continue; // cooldown anti-rafale
                }
                // Confidentialité — l'alerte porte sur le contenu d'un tiers : rien ne part
                // si le voyageur et le propriétaire de l'alerte sont masqués l'un pour
                // l'autre. Le blocage reste silencieux, l'alerte n'est ni coupée ni marquée.
                var text = com.yadony.api.notifications.NotificationTexts.corridorAlertTrip(
                        trip.getDepartureCity(), trip.getArrivalCity());
                boolean notified = notificationDispatcher.notifyUnlessBlocked(
                        alert.getOwnerId(),
                        trip.getTravelerId(),
                        text.title(),
                        text.body(),
                        Map.of(
                                "type", "CORRIDOR_ALERT",
                                "alertId", alert.getId().toString(),
                                "announcementId", trip.getId().toString(),
                                "corridor", corridor,
                                "direction", alert.getDirection().name()));
                if (!notified) {
                    // Pas d'horodatage : lastNotifiedAt sert aussi de borne « depuis » au
                    // digest. Le poser ici ferait disparaître du digest des trajets visibles
                    // publiés dans la même fenêtre, à cause d'un trajet masqué.
                    continue;
                }
                alert.setLastNotifiedAt(now);
                alertRepository.save(alert);
            } catch (Exception e) {
                log.error("[CorridorAlertTripMatch] error for alertId={}: {}",
                        alert.getId(), e.getMessage());
            }
        }
    }
}

package com.yadony.api.alerts;

import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.events.TripCapacityFreedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Un trajet complet a de nouveau de la place ({@link TripCapacityFreedEvent}) : chaque
 * abonné « Me prévenir » est notifié une fois, puis son abonnement est consommé
 * (FLUTTER-JH).
 *
 * <p>Push avec repli dans l'app : la notification est enregistrée dans le centre de
 * notifications même quand le push est coupé ou n'arrive pas. Jamais de SMS (non
 * critique). Rien ne part si, le temps que l'écouteur s'exécute, le trajet s'est rempli à
 * nouveau, a été annulé, est parti, ou n'accepte plus de remise : les abonnements restent
 * alors en attente de la prochaine place libérée.
 */
@Component
public class TripSpaceAlertListener {

    private static final Logger log = LoggerFactory.getLogger(TripSpaceAlertListener.class);

    static final String NOTIFICATION_TYPE = "TRIP_SPACE_FREED";

    private final TripSpaceAlertRepository repository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatcher notificationDispatcher;

    private Clock clock = Clock.systemUTC();

    public TripSpaceAlertListener(TripSpaceAlertRepository repository,
                                  AnnouncementRepository announcementRepository,
                                  NotificationDispatcher notificationDispatcher) {
        this.repository = repository;
        this.announcementRepository = announcementRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripCapacityFreed(TripCapacityFreedEvent event) {
        AnnouncementEntity trip = announcementRepository.findById(event.announcementId()).orElse(null);
        if (trip == null || !stillBookable(trip)) {
            return;
        }
        List<TripSpaceAlertEntity> pending = repository.findAllByAnnouncementIdAndNotifiedAtIsNull(trip.getId());
        if (pending.isEmpty()) {
            return;
        }
        String corridor = MatchingTextUtil.corridorLabel(trip.getDepartureCity(), trip.getArrivalCity());
        LocalDateTime now = LocalDateTime.now(clock);
        for (TripSpaceAlertEntity alert : pending) {
            try {
                // Consommer d'abord : seul l'appelant qui a consommé notifie, deux places
                // libérées coup sur coup ne font donc jamais deux notifications.
                if (repository.consume(alert.getId(), now) == 0) {
                    continue;
                }
                var text = NotificationTexts.tripSpaceFreed(
                        notificationDispatcher.messagesFor(alert.getSubscriberId()),
                        trip.getDepartureCity(), trip.getArrivalCity());
                // Voyageur masqué pour l'abonné : rien ne part, l'abonnement est consommé
                // quand même (le trajet ne lui est de toute façon plus montré).
                notificationDispatcher.notifyUnlessBlocked(
                        alert.getSubscriberId(),
                        trip.getTravelerId(),
                        text.title(),
                        text.body(),
                        Map.of(
                                "type", NOTIFICATION_TYPE,
                                "announcementId", trip.getId().toString(),
                                "corridor", corridor));
            } catch (Exception e) {
                log.error("[TripSpaceAlert] notification impossible alertId={} : {}",
                        alert.getId(), e.getMessage());
            }
        }
    }

    private boolean stillBookable(AnnouncementEntity trip) {
        if (trip.getStatus() != AnnouncementStatus.ACTIVE || !trip.isPubliclyVisible()) {
            return false;
        }
        if (trip.getDepartureDate() != null && trip.getDepartureDate().isBefore(LocalDate.now(clock))) {
            return false;
        }
        LocalDateTime deadline = trip.getHandoverDeadline();
        return deadline == null || deadline.isAfter(LocalDateTime.now(clock));
    }
}

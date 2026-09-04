package com.yadony.api.alerts;

import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.event.PackageRequestCreatedEvent;
import com.yadony.api.requests.repository.PackageRequestRepository;
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
 * Notification TEMPS RÉEL « un colis matche mon alerte » (côté voyageur).
 *
 * <p>Miroir de {@link CorridorAlertTripMatchListener} : à la publication d'une
 * demande de colis ({@link PackageRequestCreatedEvent}, après commit), trouve les
 * alertes TRAVELER_WANTS_PACKAGES actives qui matchent (corridor + dates + poids +
 * catégories) et notifie immédiatement leur propriétaire. Même cooldown par alerte
 * ({@link #COOLDOWN}) que côté trajets ; le digest quotidien reste en filet,
 * {@code lastNotifiedAt} étant partagé pour éviter les doublons. Jusqu'ici les
 * colis n'arrivaient qu'au digest de 9 h, un jour après.
 */
@Component
public class CorridorAlertPackageMatchListener {

    private static final Logger log =
            LoggerFactory.getLogger(CorridorAlertPackageMatchListener.class);

    /** Anti-rafale : pas plus d'une notif par alerte sur cette fenêtre. */
    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    private final PackageRequestRepository packageRequestRepository;
    private final AlertService alertService;
    private final CorridorAlertRepository alertRepository;
    private final NotificationDispatcher notificationDispatcher;

    public CorridorAlertPackageMatchListener(PackageRequestRepository packageRequestRepository,
                                             AlertService alertService,
                                             CorridorAlertRepository alertRepository,
                                             NotificationDispatcher notificationDispatcher) {
        this.packageRequestRepository = packageRequestRepository;
        this.alertService = alertService;
        this.alertRepository = alertRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPackageRequestCreated(PackageRequestCreatedEvent event) {
        PackageRequestEntity pkg = packageRequestRepository.findById(event.requestId()).orElse(null);
        if (pkg == null) {
            return;
        }
        List<CorridorAlertEntity> matches = alertService.findTravelerAlertsMatchingPackage(pkg);
        if (matches.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String corridor = MatchingTextUtil.corridorLabel(pkg.getDepartureCity(), pkg.getArrivalCity());

        for (CorridorAlertEntity alert : matches) {
            try {
                if (alert.getLastNotifiedAt() != null
                        && Duration.between(alert.getLastNotifiedAt(), now).compareTo(COOLDOWN) < 0) {
                    continue; // cooldown anti-rafale
                }
                // Confidentialité — même règle que côté trajets : rien ne part si
                // l'expéditeur et le propriétaire de l'alerte sont masqués l'un pour
                // l'autre, et l'alerte n'est pas horodatée pour ne pas priver le
                // digest des colis visibles de la même fenêtre.
                boolean notified = notificationDispatcher.notifyUnlessBlocked(
                        alert.getOwnerId(),
                        pkg.getSenderId(),
                        "Nouveau colis sur " + corridor,
                        "Un colis correspond à votre alerte",
                        Map.of(
                                "type", "CORRIDOR_ALERT",
                                "alertId", alert.getId().toString(),
                                "requestId", pkg.getId().toString(),
                                "corridor", corridor,
                                "direction", alert.getDirection().name()));
                if (!notified) {
                    continue;
                }
                alert.setLastNotifiedAt(now);
                alertRepository.save(alert);
            } catch (Exception e) {
                log.error("[CorridorAlertPackageMatch] error for alertId={}: {}",
                        alert.getId(), e.getMessage());
            }
        }
    }
}

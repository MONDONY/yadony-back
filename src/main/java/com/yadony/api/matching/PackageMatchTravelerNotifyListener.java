package com.yadony.api.matching;

import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationPrefsService;
import com.yadony.api.requests.event.PackageRequestCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification TEMPS RÉEL « un colis matche un de mes trajets » (côté voyageur).
 *
 * <p>Miroir de {@code CorridorAlertTripMatchListener} : à la création d'une demande
 * de colis ({@link PackageRequestCreatedEvent}, après commit), trouve les voyageurs
 * dont un trajet ACTIVE/FULL matche (même règle corridor + poids + fenêtre de date
 * que l'écran « Colis sur mes trajets ») et notifie immédiatement ceux qui n'ont pas
 * coupé la cloche ({@code pushTripPackageMatch}). Une demande ne déclenche qu'un seul
 * envoi par voyageur (single-shot), donc pas de cooldown nécessaire ici.
 */
@Component
public class PackageMatchTravelerNotifyListener {

    private static final Logger log =
            LoggerFactory.getLogger(PackageMatchTravelerNotifyListener.class);

    private final MatchingService matchingService;
    private final NotificationPrefsService notificationPrefsService;
    private final NotificationDispatcher notificationDispatcher;

    public PackageMatchTravelerNotifyListener(MatchingService matchingService,
                                              NotificationPrefsService notificationPrefsService,
                                              NotificationDispatcher notificationDispatcher) {
        this.matchingService = matchingService;
        this.notificationPrefsService = notificationPrefsService;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPackageRequestCreated(PackageRequestCreatedEvent event) {
        List<UUID> travelers = matchingService.findTravelersMatchingPackage(event.requestId());
        if (travelers.isEmpty()) {
            return;
        }

        String corridor = MatchingTextUtil.corridorLabel(event.departureCity(), event.arrivalCity());

        for (UUID travelerId : travelers) {
            try {
                if (!notificationPrefsService.isPackageMatchEnabled(travelerId)) {
                    continue; // le voyageur a coupé la cloche
                }
                // Confidentialité — le colis appartient à un tiers : rien ne part si
                // l'expéditeur et le voyageur sont masqués l'un pour l'autre.
                notificationDispatcher.notifyUnlessBlocked(
                        travelerId,
                        event.senderId(),
                        "Nouveau colis sur " + corridor,
                        "Un colis correspond à votre trajet",
                        Map.of(
                                "type", "PACKAGE_MATCH",
                                "requestId", event.requestId().toString(),
                                "corridor", corridor));
            } catch (Exception e) {
                log.error("[PackageMatchTravelerNotify] error for travelerId={}: {}",
                        travelerId, e.getMessage());
            }
        }
    }
}

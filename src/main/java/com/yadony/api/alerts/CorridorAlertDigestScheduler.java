package com.yadony.api.alerts;

import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.entity.PackageRequestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CorridorAlertDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(CorridorAlertDigestScheduler.class);

    private final CorridorAlertRepository alertRepository;
    private final AlertService alertService;
    private final NotificationDispatcher notificationDispatcher;
    private final BlockVisibility blockVisibility;

    public CorridorAlertDigestScheduler(CorridorAlertRepository alertRepository,
                                        AlertService alertService,
                                        NotificationDispatcher notificationDispatcher,
                                        BlockVisibility blockVisibility) {
        this.alertRepository = alertRepository;
        this.alertService = alertService;
        this.notificationDispatcher = notificationDispatcher;
        this.blockVisibility = blockVisibility;
    }

    @Scheduled(cron = "${app.alerts.digest-cron:0 0 9 * * *}", zone = "Europe/Paris")
    @Transactional
    public void runDigest() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<CorridorAlertEntity> active = alertRepository.findAllByActiveTrue();

        if (active.isEmpty()) {
            return;
        }
        log.debug("[CorridorAlertDigest] processing {} active alert(s)", active.size());

        // Confidentialité — un utilisateur masqué ne doit rien peser dans le digest de son
        // destinataire. Le jeu masqué est résolu UNE fois par destinataire et mémorisé pour
        // toute l'exécution : un propriétaire peut avoir plusieurs alertes, et un appel par
        // élément trouvé ferait exploser le nombre de requêtes sur un digest quotidien.
        Map<UUID, Set<UUID>> hiddenByOwner = new HashMap<>();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (CorridorAlertEntity alert : active) {
            // Fenêtre de dates passée : plus rien à annoncer, même si un ancien
            // match traîne dans la fenêtre « depuis ».
            if (AlertService.isExpired(alert, today)) {
                continue;
            }
            try {
                LocalDateTime since = alert.getLastNotifiedAt() != null
                        ? alert.getLastNotifiedAt()
                        : now.minusHours(24);

                Set<UUID> hidden = hiddenByOwner.computeIfAbsent(
                        alert.getOwnerId(), blockVisibility::hiddenUserIdsFor);

                boolean isTrips = alert.getDirection() == AlertDirection.SENDER_WANTS_TRIPS;
                // Le null-check précède le contains : hiddenUserIdsFor peut renvoyer un
                // Set.of() immuable, dont contains(null) lève une NPE.
                long count = isTrips
                        ? alertService.findRecentTripMatches(alert, since).stream()
                                .map(AnnouncementEntity::getTravelerId)
                                .filter(ownerOfContent -> ownerOfContent == null
                                        || !hidden.contains(ownerOfContent))
                                .count()
                        : alertService.findRecentMatches(alert, since).stream()
                                .map(PackageRequestEntity::getSenderId)
                                .filter(ownerOfContent -> ownerOfContent == null
                                        || !hidden.contains(ownerOfContent))
                                .count();
                if (count == 0) {
                    continue;
                }

                String corridor = MatchingTextUtil.corridorLabel(alert.getDepartureCity(), alert.getArrivalCity());
                var text = com.yadony.api.notifications.NotificationTexts.corridorAlertDigest(
                        isTrips, (int) Math.min(count, Integer.MAX_VALUE),
                        alert.getDepartureCity(), alert.getArrivalCity());
                String title = text.title();
                String body = text.body();
                Map<String, String> data = Map.of(
                        "type", "CORRIDOR_ALERT",
                        "alertId", alert.getId().toString(),
                        "corridor", corridor,
                        "direction", alert.getDirection().name());

                notificationDispatcher.notifyUser(alert.getOwnerId(), title, body, data);

                alert.setLastNotifiedAt(now);
                alertRepository.save(alert);
            } catch (Exception e) {
                log.error("[CorridorAlertDigest] error for alertId={}: {}",
                        alert.getId(), e.getMessage());
            }
        }
    }
}

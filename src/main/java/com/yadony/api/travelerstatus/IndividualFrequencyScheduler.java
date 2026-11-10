package com.yadony.api.travelerstatus;

import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.travelerstatus.events.IndividualFrequencyFlaggedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contrôle quotidien des particuliers fréquents (lot 4) : crée un signalement par personne retenue par
 * {@link IndividualFrequencyEvaluator} et clôt ceux dont la condition a cessé. N'écrit que dans
 * {@code individual_frequency_flags} : jamais de blocage, de sanction ni de changement de statut.
 *
 * <p>Un signalement écarté mais non clos bloque la recréation (index unique partiel) : la personne ne réapparaît
 * qu'après que la condition a cessé puis repris. La question au voyageur part une seule fois, à la création.
 *
 * <p><b>Premier passage en production</b> : tous les particuliers déjà au-dessus du seuil (ou déjà concernés par une
 * demande GP Pro « chaque semaine » refusée ou annulée dans les 30 derniers jours) sont signalés ET reçoivent la
 * question d'un coup, au premier 04:30 UTC qui suit le déploiement. Pour l'éviter ou le différer, couper le contrôle
 * avec {@code YADONY_INDIVIDUAL_FREQUENCY_SCHEDULER_ENABLED=false} (ou relever le seuil dans l'admin avant).
 */
@Component
public class IndividualFrequencyScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndividualFrequencyScheduler.class);

    private final IndividualFrequencyFlagRepository flags;
    private final IndividualFrequencyEvaluator evaluator;
    private final PlatformSettingsService settings;
    private final ApplicationEventPublisher events;
    private final IndividualFrequencyProperties properties;
    private final Clock clock;

    public IndividualFrequencyScheduler(IndividualFrequencyFlagRepository flags, IndividualFrequencyEvaluator evaluator,
                                        PlatformSettingsService settings, ApplicationEventPublisher events,
                                        IndividualFrequencyProperties properties, Clock clock) {
        this.flags = flags;
        this.evaluator = evaluator;
        this.settings = settings;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${yadony.individual-frequency.cron:0 30 4 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int threshold = settings.individualTripsAlertThreshold();
        Map<UUID, IndividualFrequencyEvaluator.Detection> detected = evaluator.detect(now, threshold);

        List<IndividualFrequencyFlagEntity> toSave = new ArrayList<>();
        Set<UUID> stillOpen = new HashSet<>();
        for (IndividualFrequencyFlagEntity flag : flags.findByResolvedAtIsNull()) {
            if (detected.containsKey(flag.getUserId())) {
                stillOpen.add(flag.getUserId());
            } else {
                flag.setResolvedAt(now);
                toSave.add(flag);
            }
        }
        int resolved = toSave.size();

        List<IndividualFrequencyFlagEntity> created = new ArrayList<>();
        detected.forEach((userId, detection) -> {
            if (stillOpen.contains(userId)) {
                return;
            }
            IndividualFrequencyFlagEntity flag = new IndividualFrequencyFlagEntity();
            flag.setUserId(userId);
            flag.setTripsCount(detection.tripsCount());
            flag.setWindowDays(IndividualFrequencyEvaluator.WINDOW_DAYS);
            flag.setThreshold(threshold);
            flag.setWeeklyApplication(detection.weeklyApplication());
            flag.setDetectedAt(now);
            created.add(flag);
        });
        toSave.addAll(created);
        if (toSave.isEmpty()) {
            return;
        }
        flags.saveAll(toSave);
        // Après saveAll : les identifiants sont posés. Les écouteurs partent après la validation de la transaction.
        created.forEach(f -> events.publishEvent(new IndividualFrequencyFlaggedEvent(f.getUserId(), f.getId())));
        log.info("Individual frequency check: {} flags created, {} resolved (threshold {})",
                created.size(), resolved, threshold);
    }
}

package com.yadony.api.travelerstatus;

import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.travelerstatus.events.IndividualFrequencyFlaggedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Contrôle quotidien des particuliers fréquents (lot 4) : crée un signalement par personne retenue par
 * {@link IndividualFrequencyEvaluator} et clôt ceux dont la condition a cessé. N'écrit que dans
 * {@code individual_frequency_flags} : jamais de blocage, de sanction ni de changement de statut.
 *
 * <p>Un signalement écarté mais non clos bloque la recréation (index unique partiel) : la personne ne réapparaît
 * qu'après que la condition a cessé puis repris. La question au voyageur (« Confirmez votre statut ») ne part qu'à
 * la création d'un signalement ET seulement si la personne n'a eu aucun signalement (résolu, écarté ou non) détecté
 * dans les {@link #QUESTION_COOLDOWN_DAYS} derniers jours : un voyageur qui oscille autour du seuil, ou un seuil
 * baissé puis remonté dans l'admin, ne la reçoit qu'une fois. Le signalement, lui, est toujours créé.
 *
 * <p>Chaque personne est traitée dans sa propre transaction : une erreur sur l'une est journalisée (un avertissement)
 * et n'empêche pas de traiter les autres.
 *
 * <p><b>Éteint par défaut</b> ({@code YADONY_INDIVIDUAL_FREQUENCY_SCHEDULER_ENABLED}, transmise par
 * {@code deploy-prod.yml} et {@code deploy-staging.yml} avec le défaut {@code false}). Cet interrupteur coupe tout :
 * le compteur de trajets publiés dans le mois ET le déclencheur « demande GP Pro chaque semaine ». <b>Premier passage après l'allumage</b> :
 * tous les particuliers qui ont déjà publié plus de trajets que le seuil dans le mois civil en cours (ou dont une
 * demande GP Pro « chaque semaine » a été refusée ou annulée dans les 30 derniers jours) sont signalés ET reçoivent la question d'un coup, au premier 04:30 UTC.
 */
@Component
public class IndividualFrequencyScheduler {

    /** Pas de seconde question dans ce délai après un signalement, quel qu'en soit le sort. */
    public static final int QUESTION_COOLDOWN_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(IndividualFrequencyScheduler.class);

    private final IndividualFrequencyFlagRepository flags;
    private final IndividualFrequencyEvaluator evaluator;
    private final PlatformSettingsService settings;
    private final ApplicationEventPublisher events;
    private final IndividualFrequencyProperties properties;
    private final Clock clock;
    private final TransactionOperations tx;

    @Autowired
    public IndividualFrequencyScheduler(IndividualFrequencyFlagRepository flags, IndividualFrequencyEvaluator evaluator,
                                        PlatformSettingsService settings, ApplicationEventPublisher events,
                                        IndividualFrequencyProperties properties, Clock clock,
                                        PlatformTransactionManager transactionManager) {
        this(flags, evaluator, settings, events, properties, clock, new TransactionTemplate(transactionManager));
    }

    /** Tests : transaction fournie (ou {@link TransactionOperations#withoutTransaction()}). */
    IndividualFrequencyScheduler(IndividualFrequencyFlagRepository flags, IndividualFrequencyEvaluator evaluator,
                                 PlatformSettingsService settings, ApplicationEventPublisher events,
                                 IndividualFrequencyProperties properties, Clock clock, TransactionOperations tx) {
        this.flags = flags;
        this.evaluator = evaluator;
        this.settings = settings;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    /** Ce qu'un passage a fait, pour le journal et les tests. */
    public record Outcome(int created, int questioned, int resolved, int failed) {
    }

    @Scheduled(cron = "${yadony.individual-frequency.cron:0 30 4 * * *}", zone = "UTC")
    public void scheduledRun() {
        run();
    }

    public Outcome run() {
        if (!properties.schedulerEnabledOrDefault()) {
            return new Outcome(0, 0, 0, 0);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int threshold = settings.individualTripsAlertThreshold();
        Map<UUID, IndividualFrequencyEvaluator.Detection> detected = evaluator.detect(now, threshold);
        Map<UUID, List<UUID>> openByUser = flags.findByResolvedAtIsNull().stream()
                .collect(Collectors.groupingBy(IndividualFrequencyFlagEntity::getUserId,
                        Collectors.mapping(IndividualFrequencyFlagEntity::getId, Collectors.toList())));

        Set<UUID> userIds = new LinkedHashSet<>(openByUser.keySet());
        userIds.addAll(detected.keySet());
        int created = 0;
        int questioned = 0;
        int resolved = 0;
        int failed = 0;
        for (UUID userId : userIds) {
            try {
                Step step = tx.execute(status -> processUser(userId, openByUser.getOrDefault(userId, List.of()),
                        detected.get(userId), now, threshold));
                if (step != null) {
                    created += step.created() ? 1 : 0;
                    questioned += step.questioned() ? 1 : 0;
                    resolved += step.resolved();
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Individual frequency check failed for user {}: {}", userId, e.getClass().getSimpleName());
            }
        }
        if (created > 0 || resolved > 0 || failed > 0) {
            log.info("Individual frequency check: {} flags created ({} questioned), {} resolved, {} failed "
                    + "(threshold {})", created, questioned, resolved, failed, threshold);
        }
        return new Outcome(created, questioned, resolved, failed);
    }

    private record Step(boolean created, boolean questioned, int resolved) {
    }

    private Step processUser(UUID userId, List<UUID> openFlagIds, IndividualFrequencyEvaluator.Detection detection,
                             LocalDateTime now, int threshold) {
        if (detection == null) {
            int resolved = 0;
            for (UUID flagId : openFlagIds) {
                resolved += flags.resolveIfOpen(flagId, now);
            }
            return new Step(false, false, resolved);
        }
        if (!openFlagIds.isEmpty()) {
            return new Step(false, false, 0);
        }
        boolean askedRecently = flags.existsByUserIdAndDetectedAtGreaterThanEqual(userId,
                now.minusDays(QUESTION_COOLDOWN_DAYS));
        IndividualFrequencyFlagEntity flag = new IndividualFrequencyFlagEntity();
        flag.setUserId(userId);
        flag.setTripsCount(detection.tripsCount());
        // Période du compte : le mois civil (Europe/Paris) de la détection, de 28 à 31 jours.
        flag.setWindowDays(IndividualFrequencyEvaluator.monthOf(now).lengthOfMonth());
        flag.setThreshold(threshold);
        flag.setWeeklyApplication(detection.weeklyApplication());
        flag.setDetectedAt(now);
        // Flush ici : une violation d'index (signalement concurrent) échoue dans la transaction de cette personne.
        IndividualFrequencyFlagEntity saved = flags.saveAndFlush(flag);
        if (!askedRecently) {
            // L'écouteur part après la validation de cette transaction.
            events.publishEvent(new IndividualFrequencyFlaggedEvent(userId, saved.getId()));
        }
        return new Step(true, !askedRecently, 0);
    }
}

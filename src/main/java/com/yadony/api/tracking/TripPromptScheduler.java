package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.TripArrivalInstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Predicate;

/**
 * Rappels du suivi approximatif : « Prêt à partir ? » à départ - 30 min, « Vous êtes arrivé ? »
 * à arrivée + 30 min.
 *
 * <p>Chaque scan sélectionne des candidats par plage de dates (requête bornée), juge l'heure
 * exacte avec le fuseau de chaque trajet, puis RÉSERVE la ligne par un UPDATE conditionnel
 * ({@code sent_at IS NULL}) avant d'envoyer : deux passages concurrents n'envoient jamais deux
 * fois. Le revers est un envoi au plus une fois (un échec d'envoi n'est pas rejoué), voulu pour
 * un rappel sans enjeu d'argent. La borne haute évite un rappel périmé et une rafale au premier
 * déploiement.
 */
@Component
public class TripPromptScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripPromptScheduler.class);

    /** Le rappel de départ part 30 min avant le départ. */
    static final Duration DEPARTURE_LEAD = Duration.ofMinutes(30);
    /** Passé 1 h après le départ prévu, le rappel de départ n'a plus de sens. */
    static final Duration DEPARTURE_GRACE = Duration.ofHours(1);
    /** Le rappel d'arrivée part 30 min après l'arrivée prévue. */
    static final Duration ARRIVAL_DELAY = Duration.ofMinutes(30);
    /** Passé 48 h après l'arrivée prévue, le rappel d'arrivée n'a plus de sens. */
    static final Duration ARRIVAL_GRACE = Duration.ofHours(48);

    private static final Predicate<AnnouncementEntity> ELIGIBLE_STATUS = a -> a.getStatus() != null
            && switch (a.getStatus()) {
                case ACTIVE, FULL, IN_PROGRESS, COMPLETED -> true;
                default -> false;
            };

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final TripArrivalInstant arrivalInstant;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public TripPromptScheduler(AnnouncementRepository announcementRepository,
                               BidRepository bidRepository,
                               TripArrivalInstant arrivalInstant,
                               ApplicationEventPublisher eventPublisher,
                               Clock clock) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.arrivalInstant = arrivalInstant;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 300_000)
    public void sendDeparturePrompts() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        List<AnnouncementEntity> candidates = announcementRepository
                .findDeparturePromptCandidates(today.minusDays(1), today.plusDays(1));
        for (AnnouncementEntity a : candidates) {
            try {
                OffsetDateTime departure = TripPositionService.departureInstant(a);
                if (departure == null
                        || now.isBefore(departure.minus(DEPARTURE_LEAD))
                        || now.isAfter(departure.plus(DEPARTURE_GRACE))) {
                    continue;
                }
                claimAndPublish(a, now, TripPromptDueEvent.Kind.DEPARTURE);
            } catch (Exception e) {
                log.error("Rappel de départ en échec pour le trajet {}", a.getId(), e);
            }
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void sendArrivalPrompts() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        List<AnnouncementEntity> candidates = announcementRepository
                .findArrivalPromptCandidates(today.minusDays(3), today.plusDays(1));
        for (AnnouncementEntity a : candidates) {
            try {
                var arrival = arrivalInstant.of(a);
                OffsetDateTime departure = TripPositionService.departureInstant(a);
                if (arrival.isEmpty()
                        // Ancien trajet sans date d'arrivée dont l'heure d'arrivée précède l'heure de départ.
                        || (departure != null && arrival.get().isBefore(departure))
                        || now.isBefore(arrival.get().plus(ARRIVAL_DELAY))
                        || now.isAfter(arrival.get().plus(ARRIVAL_GRACE))) {
                    continue;
                }
                claimAndPublish(a, now, TripPromptDueEvent.Kind.ARRIVAL);
            } catch (Exception e) {
                log.error("Rappel d'arrivée en échec pour le trajet {}", a.getId(), e);
            }
        }
    }

    private void claimAndPublish(AnnouncementEntity a, OffsetDateTime now, TripPromptDueEvent.Kind kind) {
        if (!eligibleBeforeClaim(a, kind)) {
            return;
        }
        int claimed = kind == TripPromptDueEvent.Kind.DEPARTURE
                ? announcementRepository.claimDeparturePrompt(a.getId(), now)
                : announcementRepository.claimArrivalPrompt(a.getId(), now);
        if (claimed == 1) {
            eventPublisher.publishEvent(new TripPromptDueEvent(a.getId(), a.getTravelerId(), kind));
        }
    }

    /** Trajet non annulé et au moins un colis encore porté (remis ou en transit, pas arrivé). */
    private boolean eligibleBeforeClaim(AnnouncementEntity a, TripPromptDueEvent.Kind kind) {
        if (!ELIGIBLE_STATUS.test(a)) {
            return false;
        }
        // Le voyageur a déjà touché « Je pars » : le rappel de départ n'a plus lieu d'être.
        if (kind == TripPromptDueEvent.Kind.DEPARTURE && a.getDepartedAt() != null) {
            return false;
        }
        return bidRepository.existsByAnnouncementIdAndStatusIn(a.getId(), TripPositionService.carriedStatuses());
    }

}

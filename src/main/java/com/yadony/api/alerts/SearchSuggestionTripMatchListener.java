package com.yadony.api.alerts;

import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationPrefsService;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Suggestions selon les recherches : à la publication d'un trajet ({@link AnnouncementCreatedEvent},
 * après commit), prévient les utilisateurs qui ont cherché ce corridor sans rien trouver, sans
 * avoir posé d'alerte. Push et e-mail (type {@value #TYPE}, voir {@code NotificationEmailPolicy}).
 *
 * <p>Garde-fous, dans l'ordre :
 * <ul>
 *   <li>trajet réservable et ouvert aux tiers (même règle que les alertes) ;</li>
 *   <li>recherche de moins de {@link #LOOKBACK} jours, restée sans résultat, pas encore suivie
 *   d'une suggestion ;</li>
 *   <li>pas le voyageur lui-même, ni un utilisateur que son alerte de trajet prévient déjà ;</li>
 *   <li>trajet dans la fenêtre de dates de la recherche ;</li>
 *   <li>réglage « Suggestions » et « Alertes de trajet » allumés ;</li>
 *   <li>au plus {@link #WEEKLY_CAP} suggestions par utilisateur sur {@link #CAP_WINDOW} ;</li>
 *   <li>rien si le voyageur et l'utilisateur sont masqués l'un pour l'autre.</li>
 * </ul>
 * Une suggestion envoyée éteint la recherche jusqu'à la prochaine ({@link SearchInterestRecorder}).
 */
@Component
public class SearchSuggestionTripMatchListener {

    private static final Logger log = LoggerFactory.getLogger(SearchSuggestionTripMatchListener.class);

    static final String TYPE = "SEARCH_SUGGESTION";
    static final Duration LOOKBACK = Duration.ofDays(30);
    static final Duration CAP_WINDOW = Duration.ofDays(7);
    static final int WEEKLY_CAP = 2;

    private final AnnouncementRepository announcementRepository;
    private final AlertService alertService;
    private final SearchInterestRepository interestRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final NotificationPrefsService prefsService;
    private final Clock clock;

    @Autowired
    public SearchSuggestionTripMatchListener(AnnouncementRepository announcementRepository,
                                             AlertService alertService,
                                             SearchInterestRepository interestRepository,
                                             NotificationDispatcher notificationDispatcher,
                                             NotificationPrefsService prefsService) {
        this(announcementRepository, alertService, interestRepository, notificationDispatcher,
                prefsService, Clock.systemUTC());
    }

    SearchSuggestionTripMatchListener(AnnouncementRepository announcementRepository,
                                      AlertService alertService,
                                      SearchInterestRepository interestRepository,
                                      NotificationDispatcher notificationDispatcher,
                                      NotificationPrefsService prefsService,
                                      Clock clock) {
        this.announcementRepository = announcementRepository;
        this.alertService = alertService;
        this.interestRepository = interestRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.prefsService = prefsService;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAnnouncementCreated(AnnouncementCreatedEvent event) {
        AnnouncementEntity trip = announcementRepository.findById(event.announcementId()).orElse(null);
        // Un trajet déjà complet ne vaut pas une suggestion : rien n'y est réservable.
        if (trip == null || trip.getStatus() != AnnouncementStatus.ACTIVE
                || !alertService.isTripOpenToThirdParties(trip)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (trip.getDepartureDate() != null && trip.getDepartureDate().isBefore(today)) {
            return;
        }
        List<SearchInterestEntity> interests = interestRepository.findPendingForCorridor(
                trip.getDepartureCity(), trip.getArrivalCity(), now.minus(LOOKBACK));
        if (interests.isEmpty()) {
            return;
        }
        // Une alerte de trajet préviendra déjà ces utilisateurs : pas de second message.
        Set<UUID> alerted = alertService.findSenderAlertsMatchingTrip(trip).stream()
                .map(CorridorAlertEntity::getOwnerId)
                .collect(Collectors.toSet());

        for (SearchInterestEntity interest : interests) {
            try {
                suggest(trip, interest, alerted, now, today);
            } catch (RuntimeException e) {
                log.error("[SearchSuggestion] erreur pour l'intérêt {} : {}", interest.getId(), e.getMessage());
            }
        }
    }

    private void suggest(AnnouncementEntity trip, SearchInterestEntity interest, Set<UUID> alerted,
                         LocalDateTime now, LocalDate today) {
        UUID ownerId = interest.getUserId();
        if (ownerId.equals(trip.getTravelerId()) || alerted.contains(ownerId)) {
            return;
        }
        if (!fitsDates(interest, trip.getDepartureDate(), today)) {
            return;
        }
        if (!prefsService.isAllowed(ownerId, TYPE)) {
            return;
        }
        if (interestRepository.countByUserIdAndNotifiedAtAfter(ownerId, now.minus(CAP_WINDOW)) >= WEEKLY_CAP) {
            return;
        }
        var text = NotificationTexts.searchSuggestion(notificationDispatcher.messagesFor(ownerId),
                trip.getDepartureCity(), trip.getArrivalCity(), trip.getDepartureDate());
        Map<String, String> data = new HashMap<>();
        data.put("type", "SEARCH_SUGGESTION");
        data.put("announcementId", trip.getId().toString());
        data.put("corridor", MatchingTextUtil.corridorLabel(trip.getDepartureCity(), trip.getArrivalCity()));
        boolean notified = notificationDispatcher.notifyUnlessBlocked(
                ownerId, trip.getTravelerId(), text.title(), text.body(), data);
        if (!notified) {
            // Masqués l'un pour l'autre : la recherche reste en attente d'un autre trajet.
            return;
        }
        interest.setNotifiedAt(now);
        interest.setNotifiedAnnouncementId(trip.getId());
        interestRepository.save(interest);
    }

    /**
     * Le trajet tombe-t-il dans la fenêtre de la recherche ? Une fenêtre déjà passée ne vaut
     * plus rien ; sans date, toute date de départ convient.
     */
    static boolean fitsDates(SearchInterestEntity interest, LocalDate departure, LocalDate today) {
        if (interest.getDateTo() != null && interest.getDateTo().isBefore(today)) {
            return false;
        }
        if (departure == null) {
            return true;
        }
        if (interest.getDateFrom() != null && departure.isBefore(interest.getDateFrom())) {
            return false;
        }
        return interest.getDateTo() == null || !departure.isAfter(interest.getDateTo());
    }
}

package com.yadony.api.alerts;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.events.TripSearchPerformedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Retient les recherches de trajets des utilisateurs connectés ({@link TripSearchPerformedEvent}),
 * une ligne par utilisateur et par corridor ({@link SearchInterestEntity}).
 *
 * <p>Les invités (session anonyme, aucun rôle) ne sont pas retenus : ils n'ont ni e-mail ni
 * compte à prévenir, et la purge des invités abandonnés exige qu'aucune table ne les référence.
 *
 * <p>Une nouvelle recherche réarme la suggestion : si l'utilisateur avait déjà été prévenu et
 * cherche de nouveau le même corridor, il pourra l'être encore (plafond hebdomadaire dans
 * {@link SearchSuggestionTripMatchListener}). Asynchrone et sans transaction englobante : la
 * recherche ne doit jamais attendre ni échouer à cause de cette mémoire.
 */
@Component
public class SearchInterestRecorder {

    private static final Logger log = LoggerFactory.getLogger(SearchInterestRecorder.class);

    /** Longueur des colonnes de ville (V362). */
    static final int CITY_MAX = 100;

    private final SearchInterestRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public SearchInterestRecorder(SearchInterestRepository repository, UserRepository userRepository) {
        this(repository, userRepository, Clock.systemUTC());
    }

    SearchInterestRecorder(SearchInterestRepository repository, UserRepository userRepository, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Async
    @EventListener
    public void onTripSearch(TripSearchPerformedEvent event) {
        try {
            record(event);
        } catch (DataIntegrityViolationException race) {
            // Deux pages de la même recherche en parallèle : l'autre a créé la ligne.
            log.debug("[SearchInterest] ligne déjà créée par une recherche simultanée");
        } catch (RuntimeException e) {
            log.warn("[SearchInterest] recherche non retenue : {}", e.getClass().getSimpleName());
        }
    }

    void record(TripSearchPerformedEvent event) {
        String departure = clean(event.departureCity());
        String arrival = clean(event.arrivalCity());
        if (departure == null || arrival == null || departure.equalsIgnoreCase(arrival)) {
            return;
        }
        Optional<UserEntity> user = userRepository.findByFirebaseUid(event.firebaseUid());
        if (user.isEmpty() || user.get().getRoles() == null || user.get().getRoles().isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        SearchInterestEntity interest = repository
                .findForCorridor(user.get().getId(), departure, arrival)
                .orElseGet(() -> {
                    SearchInterestEntity created = new SearchInterestEntity();
                    created.setUserId(user.get().getId());
                    created.setDepartureCity(departure);
                    created.setArrivalCity(arrival);
                    return created;
                });
        interest.setDateFrom(event.dateFrom());
        interest.setDateTo(event.dateTo());
        interest.setResultCount((int) Math.min(Math.max(event.bookableCount(), 0), Integer.MAX_VALUE));
        interest.setLastSearchedAt(now);
        // Nouvelle recherche après une suggestion : elle peut repartir pour un prochain trajet.
        interest.setNotifiedAt(null);
        interest.setNotifiedAnnouncementId(null);
        repository.save(interest);
    }

    private static String clean(String city) {
        if (city == null) {
            return null;
        }
        String trimmed = city.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > CITY_MAX ? trimmed.substring(0, CITY_MAX) : trimmed;
    }
}

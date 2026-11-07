package com.yadony.api.alerts;

import com.yadony.api.auth.events.UserFinalizedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Durée de vie des recherches retenues ({@link SearchInterestEntity}) : minimisation RGPD.
 *
 * <p>Effacées pour de bon {@link #RETENTION} après la dernière recherche (une recherche plus
 * ancienne ne déclenche de toute façon plus de suggestion), et dès la finalisation du compte.
 * Exception assumée à la suppression logique : un historique de recherches « supprimé » mais
 * conservé irait contre le but de la purge (même choix que {@code assistant_user_daily_usage}).
 */
@Component
public class SearchInterestRetention {

    private static final Logger log = LoggerFactory.getLogger(SearchInterestRetention.class);

    static final Duration RETENTION = SearchSuggestionTripMatchListener.LOOKBACK;

    private final SearchInterestRepository repository;
    private final Clock clock;

    @Autowired
    public SearchInterestRetention(SearchInterestRepository repository) {
        this(repository, Clock.systemUTC());
    }

    SearchInterestRetention(SearchInterestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.alerts.search-interests-purge-cron:0 40 4 * * *}", zone = "Europe/Paris")
    @Transactional
    public void purgeExpired() {
        int purged = repository.purgeSearchedBefore(LocalDateTime.now(clock).minus(RETENTION));
        if (purged > 0) {
            log.info("[SearchInterest] {} recherche(s) de plus de {} jours effacée(s)", purged, RETENTION.toDays());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        repository.purgeByUserId(event.getUserId());
    }
}

package com.yadony.api.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Effacement des positions floues du voyageur ({@code trip_position_samples}).
 *
 * <p>Effacement PHYSIQUE (DELETE réel) : ce sont des données de localisation personnelles qui
 * doivent disparaître pour de bon. La règle « suppression logique uniquement » vise les entités
 * métier ; le dépôt purge déjà physiquement les photos et les messages. Les journaux ne
 * contiennent que des comptes, jamais une ville ni une coordonnée.
 */
@Service
public class TripPositionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TripPositionPurgeService.class);

    /** Âge maximal d'une position, même pour un trajet jamais terminé. Défaut à confirmer par le contrôleur. */
    static final int MAX_SAMPLE_AGE_DAYS = 90;
    static final int BATCH_SIZE = 1000;
    /** Garde-fou : jamais plus de lots que cela par passage. */
    static final int MAX_BATCHES = 1000;

    private final TripPositionSampleRepository repository;
    private final Clock clock;

    public TripPositionPurgeService(TripPositionSampleRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Dernier colis livré : efface toutes les positions du trajet. Idempotent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeTrip(UUID announcementId) {
        int deleted = repository.eraseByAnnouncementId(announcementId);
        log.info("Positions purged for completed trip announcementId={} count={}", announcementId, deleted);
        return deleted;
    }

    /** Suppression de compte : efface les positions de tous les trajets du voyageur. Idempotent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeTraveler(UUID travelerId) {
        int deleted = repository.eraseByTravelerId(travelerId);
        log.info("Positions purged for deleted traveler userId={} count={}", travelerId, deleted);
        return deleted;
    }

    /** Filet de sécurité quotidien, par lots. Retourne le total effacé. */
    public int purgeClosedAndExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(MAX_SAMPLE_AGE_DAYS);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = repository.eraseClosedOrExpiredBatch(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        log.info("Position safety-net purge deleted={}", total);
        return total;
    }
}

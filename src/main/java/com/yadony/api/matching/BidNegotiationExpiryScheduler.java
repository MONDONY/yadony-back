package com.yadony.api.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Éteint les fils de négociation de trajet qui n'ont plus lieu d'être.
 *
 * <p>Deux motifs, volontairement distincts : un fil sans échange depuis
 * {@code inactivity-hours} est abandonné, et un fil dont le trajet est parti n'a plus
 * d'objet même s'il vient d'être animé. Le second cas ne se déduit pas du premier.
 *
 * <p><b>Pas</b> de {@code @Transactional} ici : chaque fil est traité dans sa propre
 * transaction par {@link BidNegotiationExpiryRunner}, pour qu'un conflit d'écriture
 * concurrente saute l'élément sans annuler le lot. Le cron est externalisé et vaut
 * {@code "-"} en profil test, où la méthode n'est donc jamais déclenchée seule.
 */
@Component
public class BidNegotiationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BidNegotiationExpiryScheduler.class);

    private final BidRepository bidRepository;
    private final BidNegotiationExpiryRunner runner;
    private final MatchingNegotiationConfig config;

    public BidNegotiationExpiryScheduler(BidRepository bidRepository,
                                         BidNegotiationExpiryRunner runner,
                                         MatchingNegotiationConfig config) {
        this.bidRepository = bidRepository;
        this.runner = runner;
        this.config = config;
    }

    @Scheduled(cron = "${yadony.matching.negotiation.expire-check-cron}")
    public void runExpiration() {
        expireStale();
        expireOnDepartedTrips();
    }

    void expireStale() {
        LocalDateTime threshold =
                LocalDateTime.now(ZoneOffset.UTC).minusHours(config.inactivityHours());
        for (BidEntity bid : bidRepository.findStaleNegotiations(threshold)) {
            safely(bid.getId(), "INACTIVE");
        }
    }

    void expireOnDepartedTrips() {
        for (BidEntity bid : bidRepository.findNegotiationsOnDepartedTrips(
                LocalDate.now(ZoneOffset.UTC))) {
            safely(bid.getId(), "TRIP_DEPARTED");
        }
    }

    private void safely(UUID bidId, String reason) {
        try {
            runner.expire(bidId, reason);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Expiration du fil de négociation {} sautée — modifié entre-temps", bidId);
        }
    }
}

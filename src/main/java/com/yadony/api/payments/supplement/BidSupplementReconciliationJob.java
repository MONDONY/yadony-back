package com.yadony.api.payments.supplement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Filet planifié des suppléments non payés (plan §5.2, points 1 et 2) : expire les demandes échues
 * et annule celles d'un colis terminé sans livraison dont l'événement aurait été perdu. Chaque
 * élément a sa propre transaction ; un élément en échec n'arrête pas le lot. Instance unique, pas
 * de verrou distribué (comme {@code StripeReleaseRecoveryJob}).
 */
@Component
public class BidSupplementReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementReconciliationJob.class);

    static final int BATCH_SIZE = 100;

    /** Un colis terminé depuis moins longtemps est laissé à son événement de fin. */
    static final Duration BID_ENDED_GRACE = Duration.ofMinutes(15);

    private final BidSupplementRepository supplements;
    private final BidSupplementService service;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;

    public BidSupplementReconciliationJob(BidSupplementRepository supplements, BidSupplementService service,
                                          BidSupplementWindow window, BidSupplementProperties properties) {
        this.supplements = supplements;
        this.service = service;
        this.window = window;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yadony.bid-supplement.reconcile-interval:PT10M}", initialDelayString = "PT2M")
    public void scheduledRun() {
        if (properties.isReconcileEnabled()) {
            run();
        }
    }

    /** Résultat d'un passage, pour les logs et les tests. */
    public record Outcome(int expired, int cancelled) {
    }

    public Outcome run() {
        int expired = 0;
        List<UUID> due = supplements.findIdsOpenExpiredBefore(BidSupplementStatus.OPEN, window.now(),
                PageRequest.of(0, BATCH_SIZE));
        for (UUID id : due) {
            try {
                if (service.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Expiration du supplément {} en échec", id, e);
            }
        }

        int cancelled = 0;
        List<UUID> ended = supplements.findBidIdsEndedWithSupplementIn(BidSupplementStatus.OPEN,
                BidSupplementService.TERMINAL_UNDELIVERED, window.now().minus(BID_ENDED_GRACE),
                PageRequest.of(0, BATCH_SIZE));
        for (UUID bidId : ended) {
            try {
                if (service.onBidEnded(bidId, "BID_ENDED")) {
                    cancelled++;
                }
            } catch (RuntimeException e) {
                log.error("Annulation du supplément du colis terminé {} en échec", bidId, e);
            }
        }
        if (expired > 0 || cancelled > 0) {
            log.info("Filet des suppléments : {} expiré(s), {} annulé(s) sur colis terminé", expired, cancelled);
        }
        return new Outcome(expired, cancelled);
    }
}

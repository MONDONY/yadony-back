package com.yadony.api.payments.fee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reprise horaire des dédommagements d'annulation non versés : toutes les lignes {@code PAYOUT_PENDING} (compte
 * Connect absent ou inutilisable, voyageur gelé, litige, refus Stripe…) et les lignes {@code RETAINED} réglées
 * depuis plus de {@link #RETAINED_GRACE} (écouteur perdu : redéploiement entre le commit et le versement, base
 * indisponible au moment de la mise en attente).
 *
 * <p>Idempotent : chaque essai repasse par {@link CancellationFeePayoutService#payOut}, même clé d'idempotence
 * Stripe et recherche du Transfer existant, une ligne versée n'est plus jamais reprise. Sans fin de reprise : une
 * ligne reste en attente tant que la cause dure (l'argent ne repart jamais à l'expéditeur), avec une seule alerte
 * admin ouverte quel que soit le nombre d'essais ({@code AdminAlertEscalator#raiseOnce}).
 *
 * <p>Sans transaction englobante : chaque ligne a les siennes (celles de {@code payOut}), une ligne en échec ne
 * bloque pas les suivantes et aucune connexion n'est tenue pendant les appels Stripe.
 */
@Component
public class CancellationFeePayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(CancellationFeePayoutScheduler.class);

    /** Délai laissé à l'écouteur du règlement avant qu'une ligne RETAINED soit reprise ici. */
    static final Duration RETAINED_GRACE = Duration.ofMinutes(10);

    private final CancellationFeePayoutPort port;
    private final CancellationFeePayoutService payoutService;
    private final Clock clock;

    public CancellationFeePayoutScheduler(CancellationFeePayoutPort port, CancellationFeePayoutService payoutService,
                                          Clock clock) {
        this.port = port;
        this.payoutService = payoutService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void retryPayouts() {
        run();
    }

    /** Un passage ; rend le nombre de dédommagements versés. */
    int run() {
        List<UUID> retries;
        try {
            retries = port.findPayoutRetries(clock.instant().minus(RETAINED_GRACE));
        } catch (RuntimeException e) {
            log.error("Reprise des dédommagements d'annulation : lignes illisibles", e);
            return 0;
        }
        int paid = 0;
        for (UUID feeId : retries) {
            try {
                if (payoutService.payOut(feeId)) {
                    paid++;
                }
            } catch (RuntimeException e) {
                log.error("Reprise du dédommagement {} en échec", feeId, e);
            }
        }
        if (!retries.isEmpty()) {
            log.info("Reprise des dédommagements d'annulation : {} repris, {} versés", retries.size(), paid);
        }
        return paid;
    }
}

package com.yadony.api.payments.supplement;

import com.yadony.api.payments.cash.CommissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Filet planifié du rail espèces (plan §4.4 et §5.2) :
 * <ol>
 *   <li>une demande restée en {@code COMMISSION_PENDING} plus de
 *       {@code commission-confirm-window} (30 min : 3DS jamais confirmée, issue Stripe inconnue)
 *       est annulée ; sa PaymentIntent de commission est annulée ensuite ;</li>
 *   <li>une demande close (ou un supplément espèces terminé) dont la commission reste prélevée
 *       ({@code CHARGED}) ou en 3DS ({@code REQUIRES_3DS}) est soldée : commission rendue,
 *       PaymentIntent annulée. Rattrape un événement perdu ou un remboursement Stripe en échec.</li>
 * </ol>
 * Chaque élément a ses propres transactions ; un élément en échec n'arrête pas le lot. Instance
 * unique, comme les autres filets du supplément.
 */
@Component
public class BidSupplementCashReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCashReconciliationJob.class);

    static final int BATCH_SIZE = 100;

    /** Laisse à l'événement de clôture le temps d'agir avant que le filet ne s'en mêle. */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(5);

    private final BidSupplementRepository supplements;
    private final BidSupplementService service;
    private final BidSupplementCashRefunds refunds;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;

    public BidSupplementCashReconciliationJob(BidSupplementRepository supplements, BidSupplementService service,
                                              BidSupplementCashRefunds refunds, BidSupplementWindow window,
                                              BidSupplementProperties properties) {
        this.supplements = supplements;
        this.service = service;
        this.refunds = refunds;
        this.window = window;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yadony.bid-supplement.reconcile-interval:PT10M}", initialDelayString = "PT3M")
    public void scheduledRun() {
        if (properties.isReconcileEnabled()) {
            run();
        }
    }

    /** Résultat d'un passage, pour les logs et les tests. */
    public record Outcome(int cancelled, int settled) {
    }

    public Outcome run() {
        LocalDateTime now = window.now();
        LocalDateTime unconfirmedBefore = now.minus(properties.getCommissionConfirmWindow());
        int cancelled = 0;
        for (UUID id : supplements.findIdsAwaitingCommissionBefore(unconfirmedBefore, PageRequest.of(0, BATCH_SIZE))) {
            try {
                if (service.cancelUnconfirmedCommission(id, unconfirmedBefore)) {
                    cancelled++;
                }
            } catch (RuntimeException e) {
                log.error("Annulation de la demande {} en attente de commission en échec", id, e);
            }
        }

        int settled = 0;
        List<UUID> toSettle = supplements.findCashIdsWithCommissionToSettle(BidSupplementCashLedger.SETTLEABLE,
                EnumSet.of(CommissionStatus.CHARGED, CommissionStatus.REQUIRES_3DS), now.minus(SETTLE_GRACE),
                PageRequest.of(0, BATCH_SIZE));
        for (UUID id : toSettle) {
            try {
                if (refunds.settle(id)) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.error("Commission du supplément espèces {} : solde en échec", id, e);
            }
        }
        if (cancelled > 0 || settled > 0) {
            log.info("Filet espèces des suppléments : {} demande(s) annulée(s), {} commission(s) soldée(s)",
                    cancelled, settled);
        }
        return new Outcome(cancelled, settled);
    }
}

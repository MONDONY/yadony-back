package com.yadony.api.payments.cash.job;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.BidCancelledCommissionRefundListener;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Filet des recrédits de commission. Ils sont portés par des écouteurs après commit
 * ({@link BidCancelledCommissionRefundListener}, {@code CommissionRefundListener},
 * {@code WalletCancellationListener}) : si l'un échoue (verrou, base indisponible), Spring
 * journalise l'exception et rien ne la rejoue. La commission restait alors CHARGED pour toujours,
 * contre la règle du propriétaire « la commission est toujours rendue » (19/10).
 *
 * <p>Toutes les 30 minutes, les colis espèces clos sans livraison depuis plus d'une heure et
 * moins de {@link #WINDOW} dont la commission est encore CHARGED sont rejoués un par un, chacun
 * dans sa transaction. La fenêtre bornée garde hors d'atteinte les colis antérieurs à la règle
 * (absence et colis refusé gardaient alors la commission) : ceux-là ne sont que signalés par
 * INV-10. Le recrédit verrouille le colis et relit son statut : un écouteur qui aboutit en même
 * temps ne provoque jamais de second recrédit.
 */
@Component
public class CommissionRefundRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(CommissionRefundRecoveryJob.class);

    /** Statuts de colis clos sans livraison, dont la commission revient toujours au voyageur. */
    static final Set<BidStatus> CLOSED_WITHOUT_DELIVERY = EnumSet.of(
            BidStatus.CANCELLED, BidStatus.REJECTED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED);

    /** Laisse aux écouteurs le temps d'aboutir avant de les considérer en échec. */
    static final Duration GRACE = Duration.ofHours(1);

    /** Ancienneté maximale d'un colis rattrapé automatiquement. */
    static final Duration WINDOW = Duration.ofDays(7);

    static final int BATCH = 100;

    private final BidRepository bidRepository;
    private final BidCancelledCommissionRefundListener refunder;
    private final AdminAlertService adminAlertService;
    private final Clock clock;

    @Autowired
    public CommissionRefundRecoveryJob(BidRepository bidRepository,
                                       BidCancelledCommissionRefundListener refunder,
                                       AdminAlertService adminAlertService) {
        this(bidRepository, refunder, adminAlertService, Clock.systemUTC());
    }

    CommissionRefundRecoveryJob(BidRepository bidRepository,
                                BidCancelledCommissionRefundListener refunder,
                                AdminAlertService adminAlertService,
                                Clock clock) {
        this.bidRepository = bidRepository;
        this.refunder = refunder;
        this.adminAlertService = adminAlertService;
        this.clock = clock;
    }

    /** Sans transaction englobante : chaque colis est rejoué dans la sienne ({@code recover}). */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void recoverMissedRefunds() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<UUID> bidIds = bidRepository.findChargedCommissionOnClosedBids(
                PaymentMethod.CASH, CommissionStatus.CHARGED, CLOSED_WITHOUT_DELIVERY,
                now.minus(GRACE), now.minus(WINDOW), PageRequest.of(0, BATCH));
        List<String> recovered = new ArrayList<>();
        for (UUID bidId : bidIds) {
            try {
                if (refunder.recover(bidId)) {
                    recovered.add(bidId.toString());
                }
            } catch (RuntimeException e) {
                log.error("Rattrapage du recrédit de commission en échec pour le colis {}", bidId, e);
            }
        }
        if (!recovered.isEmpty()) {
            log.warn("Commissions recréditées par le rattrapage : {}", recovered);
            adminAlertService.raise("commission-refund-recovered",
                    "Commission rendue par le rattrapage : le recrédit au moment de l'annulation avait échoué",
                    Map.of("count", recovered.size(), "bidIds", String.join(",", recovered)));
        }
    }
}

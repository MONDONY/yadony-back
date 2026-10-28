package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Story 9.6 — remise non effectuée à l'échéance (date limite + 1 h de grâce), sans scan ni
 * signalement en cours.
 * <ul>
 *   <li>carte, mobile money : le voyageur est déclaré absent ({@link NoShowService#recordTravelerNoShow}) ;</li>
 *   <li>espèces : le colis est annulé sans fautif, commission rendue au voyageur
 *       ({@link NoShowService#expireUnconfirmedCashHandover}, FLUTTER-QR). Rien n'est encaissé
 *       par Yadony côté expéditeur, et sans signalement rien n'indique qui n'est pas venu.</li>
 * </ul>
 */
@Component
public class NoShowScheduler {

    private static final Logger log = LoggerFactory.getLogger(NoShowScheduler.class);
    static final Duration NO_SHOW_GRACE = Duration.ofHours(1);

    private final BidRepository bidRepository;
    private final NoShowService noShowService;

    public NoShowScheduler(BidRepository bidRepository,
                           NoShowService noShowService) {
        this.bidRepository = bidRepository;
        this.noShowService = noShowService;
    }

    /**
     * Volontairement SANS transaction englobante : chaque colis est traité dans la sienne
     * ({@code @Transactional} des méthodes de {@link NoShowService}). Une transaction unique
     * pour tout le lot avait deux défauts : une erreur sur un colis rendait la transaction
     * rollback-only et annulait tout le lot (aucun remboursement de commission, sans alerte), et
     * les colis chargés par {@link BidRepository#findNoShowBids} restaient en cache, si bien que
     * le colis relu « sous verrou » gardait un statut périmé face à un signalement concurrent.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC") // every hour, UTC
    public void detectNoShows() {
        detectNoShowsAt(Instant.now());
    }

    /**
     * Un passage à l'instant {@code now}.
     *
     * <p>{@code bid.handoverDeadline} est stockée en UTC (copiée du trajet). La lire comme
     * une heure murale du fuseau du trajet déclarait l'absence deux heures trop tôt à Paris
     * en été (YADONY-FLUTTER-PROD-1R) : la requête et la décision comparent désormais en UTC
     * ({@link HandoverDeadlineRules#deadlineInstant}).
     */
    void detectNoShowsAt(Instant now) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(now.minus(NO_SHOW_GRACE), ZoneOffset.UTC);
        List<BidEntity> candidates = bidRepository.findNoShowBids(cutoff);

        List<BidEntity> noShowBids = candidates.stream()
                .filter(b -> isPastGrace(b, now))
                .toList();

        log.debug("No-show scheduler: {} bids to process ({} candidates)", noShowBids.size(), candidates.size());

        for (BidEntity bid : noShowBids) {
            try {
                if (bid.getPaymentMethod() == PaymentMethod.CASH) {
                    // Espèces : aucune trace de qui n'est pas venu, pas de fautif automatique
                    // (FLUTTER-QR). Annulé, commission rendue, personne de sanctionné.
                    noShowService.expireUnconfirmedCashHandover(bid.getId());
                    continue;
                }
                noShowService.recordTravelerNoShow(bid.getId(), "scheduler");
            } catch (Exception e) {
                log.error("Error processing no-show for bid {}: {}", bid.getId(), e.getMessage(), e);
            }
        }
    }

    /** Date limite du colis (UTC) + 1 h de grâce, strictement dépassée. */
    static boolean isPastGrace(BidEntity bid, Instant now) {
        if (bid.getHandoverDeadline() == null) {
            // La requête exclut déjà ce cas ; défensif, on s'en remet à elle.
            return true;
        }
        Instant deadline = HandoverDeadlineRules.deadlineInstant(bid.getHandoverDeadline());
        return deadline.plus(NO_SHOW_GRACE).isBefore(now);
    }
}

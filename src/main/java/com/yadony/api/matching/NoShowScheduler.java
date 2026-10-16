package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final AnnouncementRepository announcementRepository;
    private final NoShowService noShowService;

    public NoShowScheduler(BidRepository bidRepository,
                           AnnouncementRepository announcementRepository,
                           NoShowService noShowService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.noShowService = noShowService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC") // every hour, UTC
    @Transactional
    public void detectNoShows() {
        detectNoShowsAt(Instant.now());
    }

    /**
     * Un passage à l'instant {@code now}.
     *
     * <p>{@code bid.handoverDeadline} est une heure murale du fuseau du trajet. La requête
     * la comparait à une heure UTC : selon le corridor, le no-show partait une à deux heures
     * trop tôt ou trop tard. Elle sert désormais de filtre large (borne élargie du plus grand
     * décalage en avance sur UTC, 14 h) et la décision exacte se prend ici, trajet par trajet,
     * dans son fuseau ({@link HandoverDeadlineRules#deadlineInstant}).
     */
    void detectNoShowsAt(Instant now) {
        LocalDateTime broadCutoff = LocalDateTime.ofInstant(now.minus(NO_SHOW_GRACE), ZoneOffset.UTC)
                .plusHours(HandoverDeadlineRules.MAX_ZONE_AHEAD_HOURS);
        List<BidEntity> candidates = bidRepository.findNoShowBids(broadCutoff);
        Map<UUID, AnnouncementEntity> announcements = announcementRepository.findAllById(
                        candidates.stream().map(BidEntity::getAnnouncementId).distinct().toList())
                .stream().collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity()));

        List<BidEntity> noShowBids = candidates.stream()
                .filter(b -> isPastGrace(b, announcements.get(b.getAnnouncementId()), now))
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

    /** Date limite du colis (fuseau du trajet) + 1 h de grâce, strictement dépassée. */
    static boolean isPastGrace(BidEntity bid, AnnouncementEntity announcement, Instant now) {
        if (bid.getHandoverDeadline() == null) {
            // La requête exclut déjà ce cas ; défensif, on s'en remet à elle.
            return true;
        }
        String timezone = announcement != null ? announcement.getTimezone() : null;
        Instant deadline = HandoverDeadlineRules.deadlineInstant(bid.getHandoverDeadline(), timezone);
        return deadline.plus(NO_SHOW_GRACE).isBefore(now);
    }
}

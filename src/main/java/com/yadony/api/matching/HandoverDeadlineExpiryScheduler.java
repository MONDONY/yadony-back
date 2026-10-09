package com.yadony.api.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Annule automatiquement, à la date limite de dépôt du trajet, les demandes qui n'engagent
 * encore personne (FLUTTER-GA) : en attente de réponse, en attente de paiement, et fils de
 * négociation ouverts ({@link HandoverDeadlineRules#EXPIRABLE_STATUSES}).
 *
 * <p>Avant, ces demandes survivaient jusqu'au délai de 24 h ou jusqu'au départ, et pouvaient
 * être acceptées et payées alors que le voyageur n'attendait plus de colis. Un colis déjà
 * accepté n'est jamais touché ici.
 *
 * <p>La date limite est lue sur le trajet à chaque passage : un report (nouvelle date limite)
 * est donc pris en compte sans rien faire, et un trajet sans date limite n'expire jamais.
 * Chaque étape d'un voyage est une annonce à part, avec sa propre date limite.
 *
 * <p><b>Pas</b> de {@code @Transactional} ici : chaque demande vit dans sa propre transaction
 * (runners). Cron externalisé, {@code "-"} en profil test.
 */
@Component
public class HandoverDeadlineExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HandoverDeadlineExpiryScheduler.class);

    /** Listes « /me » à vider quand une demande change d'état (mêmes noms que BidService). */
    static final List<String> CACHES = List.of("bids-me", "traveler-bids-me");

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final HandoverDeadlineExpiryRunner runner;
    private final BidNegotiationExpiryRunner negotiationRunner;
    private final CacheManager cacheManager;

    public HandoverDeadlineExpiryScheduler(BidRepository bidRepository,
                                           AnnouncementRepository announcementRepository,
                                           HandoverDeadlineExpiryRunner runner,
                                           BidNegotiationExpiryRunner negotiationRunner,
                                           CacheManager cacheManager) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.runner = runner;
        this.negotiationRunner = negotiationRunner;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "${yadony.matching.handover-deadline.expire-check-cron}")
    public void runExpiration() {
        expireAt(Instant.now());
    }

    /** Un passage, à l'instant {@code now} : testable sans dépendre de l'horloge. */
    int expireAt(Instant now) {
        LocalDateTime upperBound = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
                .plusHours(HandoverDeadlineRules.MAX_ZONE_AHEAD_HOURS);
        List<UUID> candidates = bidRepository.findIdsForHandoverDeadlineExpiry(
                HandoverDeadlineRules.EXPIRABLE_STATUSES, upperBound);
        int changed = 0;
        for (UUID bidId : candidates) {
            try {
                if (handle(bidId, now)) {
                    changed++;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Expiration date limite de dépôt du bid {} sautée : modifié entre-temps", bidId);
            } catch (RuntimeException e) {
                // Une demande en échec ne doit jamais bloquer les autres.
                log.error("Expiration date limite de dépôt du bid {} échouée : {}", bidId, e.toString());
            }
        }
        if (changed > 0) {
            evictCaches();
            log.info("{} demande(s) annulée(s) ou close(s) : date limite de dépôt passée", changed);
        }
        return changed;
    }

    private boolean handle(UUID bidId, Instant now) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            return false;
        }
        if (bid.getStatus() == BidStatus.NEGOTIATING) {
            AnnouncementEntity announcement =
                    announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
            if (announcement == null || !announcement.isHandoverDeadlinePassed(now)) {
                return false;
            }
            negotiationRunner.expire(bidId, HandoverDeadlineRules.EXPIRY_REASON);
            return true;
        }
        return runner.expire(bidId, now) != HandoverDeadlineExpiryRunner.Outcome.IGNORED;
    }

    private void evictCaches() {
        for (String name : CACHES) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}

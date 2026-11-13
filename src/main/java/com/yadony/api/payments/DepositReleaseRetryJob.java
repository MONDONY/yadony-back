package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rattrapage du versement au dépôt. Le versement part au {@code ParcelDepositedEvent}, écouté après le commit du scan
 * DEPART par un exécuteur asynchrone en mémoire : un redéploiement entre le commit et l'écouteur le perd, et un compte
 * Connect pas prêt au dépôt le laisse en séquestre. Sans cette passe, rien ne le relançait avant la livraison (carte,
 * des semaines plus tard pour un bateau) ou jamais (mobile money).
 *
 * <p>Toutes les {@code interval} (10 min par défaut), reprend les colis payés au dépôt, déposés depuis au moins
 * {@code min-age} et au plus {@code max-age}, dont le paiement plateforme (carte ou mobile money) est encore en
 * séquestre sans garde posée (litige bancaire, remboursement partiel, retenue), et relance EXACTEMENT le versement du
 * dépôt ({@link DeliveryEventListener#releaseAtDeposit}) : mêmes gardes, relecture « déposé et versable » sous le verrou
 * du colis, claim atomique ESCROW → RELEASED, même clé Stripe {@code transfer-<paymentId>-r<n>}, même payout pawaPay.
 * Un versement concurrent (événement retardé, séquestre tardif, libération admin) perd le claim : un seul mouvement.
 *
 * <p>Borné : {@code batch-size} colis par passage, nouvel essai d'un même colis espacé (10 min, puis doublé jusqu'à
 * 6 h), arrêt après {@code max-age} (l'alerte J+48 de {@link EscrowScheduler} reste le dernier filet). Un colis encore
 * impayé {@code alert-after} après son dépôt lève l'alerte {@code DEPOSIT_STALLED_<bidId>} (une seule ouverte), close
 * dès qu'il est versé.
 */
@Component
public class DepositReleaseRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DepositReleaseRetryJob.class);

    /** Préfixe (16) + UUID du colis (36) = 52, sous la limite de 60 de {@code admin_alerts.type}. */
    public static final String STALLED_ALERT_PREFIX = "DEPOSIT_STALLED_";

    static final Duration FIRST_BACKOFF = Duration.ofMinutes(10);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final DeliveryEventListener deliveryRelease;
    private final AdminAlertEscalator alertEscalator;
    private final boolean enabled;
    private final Duration minAge;
    private final Duration maxAge;
    private final Duration alertAfter;
    private final int batchSize;
    private final Clock clock;

    /** Prochain essai et délai courant, par colis ; perdu au redémarrage (le colis est alors repris au passage suivant). */
    private final Map<UUID, Attempt> agenda = new ConcurrentHashMap<>();

    private record Attempt(Instant next, Duration backoff) {}

    @org.springframework.beans.factory.annotation.Autowired
    public DepositReleaseRetryJob(PaymentRepository paymentRepository, BidRepository bidRepository,
                                  AnnouncementRepository announcementRepository, DeliveryEventListener deliveryRelease,
                                  AdminAlertEscalator alertEscalator,
                                  @Value("${yadony.payments.deposit-retry.enabled:true}") boolean enabled,
                                  @Value("${yadony.payments.deposit-retry.min-age:PT10M}") Duration minAge,
                                  @Value("${yadony.payments.deposit-retry.max-age:P3D}") Duration maxAge,
                                  @Value("${yadony.payments.deposit-retry.alert-after:PT6H}") Duration alertAfter,
                                  @Value("${yadony.payments.deposit-retry.batch-size:20}") int batchSize) {
        this(paymentRepository, bidRepository, announcementRepository, deliveryRelease, alertEscalator, enabled, minAge,
                maxAge, alertAfter, batchSize, Clock.systemUTC());
    }

    DepositReleaseRetryJob(PaymentRepository paymentRepository, BidRepository bidRepository,
                           AnnouncementRepository announcementRepository, DeliveryEventListener deliveryRelease,
                           AdminAlertEscalator alertEscalator, boolean enabled, Duration minAge, Duration maxAge,
                           Duration alertAfter, int batchSize, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.deliveryRelease = deliveryRelease;
        this.alertEscalator = alertEscalator;
        this.enabled = enabled;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.alertAfter = alertAfter;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${yadony.payments.deposit-retry.interval:PT10M}",
            initialDelayString = "${yadony.payments.deposit-retry.initial-delay:PT4M}")
    public void scheduledRun() {
        if (enabled) {
            run();
        }
    }

    /**
     * Un passage, hors de toute transaction (le versement refuse d'être appelé sous une transaction : appel Stripe).
     *
     * @return le nombre de colis versés pendant ce passage
     */
    public int run() {
        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        agenda.values().removeIf(a -> a.next().isBefore(now.minus(maxAge)));
        List<PaymentRepository.DepositRetryCandidate> candidates = paymentRepository.findDepositReleaseRetryCandidates(
                nowUtc.minus(minAge), nowUtc.minus(maxAge), PageRequest.of(0, Math.max(1, batchSize) * 5));
        int tried = 0;
        int released = 0;
        for (PaymentRepository.DepositRetryCandidate candidate : candidates) {
            if (tried >= batchSize) {
                break;
            }
            UUID bidId = candidate.getBidId();
            Attempt attempt = agenda.get(bidId);
            if (attempt != null && attempt.next().isAfter(now)) {
                continue;
            }
            tried++;
            if (retry(bidId)) {
                released++;
                agenda.remove(bidId);
                resolveStalled(bidId);
                continue;
            }
            Duration backoff = attempt == null ? FIRST_BACKOFF : min(attempt.backoff().multipliedBy(2), MAX_BACKOFF);
            agenda.put(bidId, new Attempt(now.plus(backoff), backoff));
            if (candidate.getDepositedAt() != null && candidate.getDepositedAt().isBefore(nowUtc.minus(alertAfter))) {
                raiseStalled(candidate);
            }
        }
        if (tried > 0) {
            log.info("Rattrapage du versement au dépôt : {} colis repris, {} versés", tried, released);
        }
        return released;
    }

    /** Relance le versement du dépôt du colis ; vrai si l'argent est parti pendant cet essai. */
    private boolean retry(UUID bidId) {
        try {
            BidEntity bid = bidRepository.findById(bidId).orElse(null);
            if (bid == null) {
                return false;
            }
            UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                    .map(AnnouncementEntity::getTravelerId).orElse(null);
            if (travelerId == null) {
                log.warn("Rattrapage du dépôt du colis {} : voyageur introuvable", bidId);
                return false;
            }
            EscrowReleaseOutcome outcome = deliveryRelease.releaseAtDeposit(
                    new ParcelDepositedEvent(bidId, bid.getSenderId(), travelerId));
            log.info("Rattrapage du dépôt du colis {} : {}", bidId, outcome);
            return outcome.released();
        } catch (RuntimeException e) {
            log.error("Rattrapage du dépôt du colis {} en échec", bidId, e);
            return false;
        }
    }

    private void raiseStalled(PaymentRepository.DepositRetryCandidate candidate) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("bidId", candidate.getBidId().toString());
            context.put("paymentId", candidate.getPaymentId().toString());
            context.put("depositedAt", String.valueOf(candidate.getDepositedAt()));
            alertEscalator.raiseOnce(STALLED_ALERT_PREFIX + candidate.getBidId(),
                    "Colis " + candidate.getBidId() + " déposé depuis plus de " + alertAfter.toHours()
                            + " h, transporteur toujours pas payé malgré les nouvelles tentatives (paiement "
                            + candidate.getPaymentId() + " en séquestre) : voir les alertes de versement du colis",
                    context);
        } catch (RuntimeException e) {
            log.error("Alerte {}{} non levée", STALLED_ALERT_PREFIX, candidate.getBidId(), e);
        }
    }

    private void resolveStalled(UUID bidId) {
        try {
            alertEscalator.resolveOpen(STALLED_ALERT_PREFIX + bidId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close : {}", STALLED_ALERT_PREFIX, bidId, e.getMessage());
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}

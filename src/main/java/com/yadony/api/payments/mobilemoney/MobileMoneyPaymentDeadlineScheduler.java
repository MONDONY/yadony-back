package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chaque minute ({@code yadony.pawapay.deadline-cron}) : annule les bids mobile money dont le
 * délai de paiement (30 min après acceptation, {@code BidEntity#awaitingPaymentExpiresAt}) est
 * dépassé.
 *
 * <p><b>Idempotent</b>, mais pas par la sélection ci-dessous — elle peut retomber sur la même
 * ligne à chaque tick tant qu'elle n'a pas bougé : c'est la primitive atomique
 * {@link com.yadony.api.payments.PaymentRepository#markCancelledIfPending} au cœur de
 * {@link MobileMoneyBidPaymentService#expire} qui garantit qu'un même bid n'est jamais traité
 * deux fois. Chaque bid vit dans SA PROPRE transaction ({@code expire} est
 * {@code REQUIRES_NEW}) : l'échec de l'un n'empêche jamais les suivants — même motif que
 * {@code PawapayReconciliationPoller#reconcile}, dont ce scheduler reprend aussi le garde-fou
 * try/catch par élément.
 *
 * <p>Le lot est borné à {@link #BATCH_SIZE} (même motif, même valeur que
 * {@code PawapayReconciliationPoller}) : un incident qui laisserait s'accumuler des centaines de
 * bids en souffrance ne doit jamais faire durer un seul passage des heures durant, sur l'unique
 * pool de scheduling partagé par tous les crons du dépôt. Triés par
 * {@code awaitingPaymentExpiresAt} croissant : les plus anciens d'abord, la file finit par se
 * vider passage après passage même si le flux entrant ne tarit jamais. Seuls les identifiants
 * sont chargés : {@code expire} relit de toute façon le bid sous verrou.
 *
 * <p>Ne dépend pas de {@code yadony.pawapay.enabled} : un bid déjà en vol doit pouvoir expirer
 * même si l'exploitant vient de couper le rail (l'argent, lui, doit revenir au voyageur).
 *
 * <p>{@link MobileMoneyBidPaymentService#expire} ne fait qu'annuler (ou non) le bid, à
 * l'intérieur de ses deux verrous (paiement, bid) — il ne lève pas d'alerte et n'évince pas le
 * cache lui-même : {@code AdminAlertService#raise} poste sur Telegram par HTTP, retarder
 * {@code confirmEscrow} (la transaction même qu'on attend pour résoudre l'anomalie) par ces
 * verrous serait le pire endroit possible pour un appel réseau synchrone. C'est CE scheduler qui
 * agit sur l'{@code ExpireOutcome} rendu, APRÈS le retour d'{@code expire} (donc après le commit
 * de son {@code REQUIRES_NEW}, hors tout verrou) :
 * <ul>
 *   <li>{@code CANCELLED} → éviction du cache {@value #SEARCH_CACHE_NAME} (même cache, même
 *       éviction totale que {@code AnnouncementSearchBlockEvictionListener}, ici programmatique
 *       via {@link CacheManager} plutôt que déclarative) ;</li>
 *   <li>{@code PAYMENT_MISSING} → alerte administrateur dédupliquée par bid
 *       ({@link AdminAlertEscalator}) : sans cette dédup, un bid resté en échec fermé (donc
 *       resélectionné à chaque tick) spammerait Sentry et Telegram indéfiniment ;</li>
 *   <li>{@code DEPOSIT_COMPLETED_NOT_APPLIED} → réparation : rejoue
 *       {@code MobileMoneyBidPaymentService#confirmEscrow} via {@link #repairDepositCompletedNotApplied}
 *       au lieu de seulement alerter — la même alerte que ci-dessus ne reste qu'un FILET si la
 *       réparation échoue elle-même ;</li>
 *   <li>{@code IGNORED} → rien.</li>
 * </ul>
 */
@Component
public class MobileMoneyPaymentDeadlineScheduler {

    /**
     * Borne un passage — sans elle, un incident prolongé (paiement bloqué en masse) pourrait
     * accumuler des centaines de bids en souffrance et faire durer une exécution des heures
     * durant, sur l'unique pool de scheduling partagé par tous les crons du dépôt.
     */
    static final int BATCH_SIZE = 200;

    static final String SEARCH_CACHE_NAME = "announcements-search";
    /**
     * {@code admin_alerts.type} est {@code VARCHAR(60)} : préfixe + UUID (36) doit rester sous
     * 60 — {@link AdminAlertEscalator} refuse bruyamment un type trop long, ces deux préfixes
     * font 54 et 56 avec l'UUID (voir {@code MobileMoneyPaymentDeadlineSchedulerTest}).
     */
    static final String PAYMENT_MISSING_ALERT_PREFIX = "MM_EXP_NO_PAYMENT_";
    static final String DEPOSIT_COMPLETED_ALERT_PREFIX = "MM_EXP_DEPOSIT_DONE_";

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPaymentDeadlineScheduler.class);

    private final BidRepository bidRepository;
    private final MobileMoneyBidPaymentService service;
    private final AdminAlertEscalator alerts;
    private final CacheManager cacheManager;

    public MobileMoneyPaymentDeadlineScheduler(BidRepository bidRepository, MobileMoneyBidPaymentService service,
                                               AdminAlertEscalator alerts, CacheManager cacheManager) {
        this.bidRepository = bidRepository;
        this.service = service;
        this.alerts = alerts;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "${yadony.pawapay.deadline-cron}")
    public void expireUnpaidBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<UUID> due = bidRepository.findIdsByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                BidStatus.AWAITING_PAYMENT, PaymentMethod.MOBILE_MONEY, now,
                PageRequest.of(0, BATCH_SIZE, Sort.by("awaitingPaymentExpiresAt").ascending()));
        for (UUID bidId : due) {
            try {
                handle(bidId, service.expire(bidId));
            } catch (Exception e) {
                // Une expiration en échec (bug, contrainte DB…) ne doit jamais interrompre les
                // autres — même motif que PawapayReconciliationPoller#reconcile.
                log.error("Expiration mobile money du bid {} échouée : {}", bidId, e.toString());
            }
        }
    }

    /** Hors transaction et hors verrou (voir Javadoc de la classe) : agit sur l'issue rendue. */
    private void handle(UUID bidId, MobileMoneyBidPaymentService.ExpireOutcome outcome) {
        switch (outcome) {
            case CANCELLED -> evictSearchCache();
            case PAYMENT_MISSING -> alerts.raiseOnce(PAYMENT_MISSING_ALERT_PREFIX + bidId,
                    "Bid " + bidId + " AWAITING_PAYMENT mobile money sans paiement PAWAPAY associé",
                    Map.of("bidId", bidId.toString()));
            case DEPOSIT_COMPLETED_NOT_APPLIED -> repairDepositCompletedNotApplied(bidId);
            case IGNORED -> { }
        }
    }

    /**
     * Deposit pawaPay COMPLETED, paiement encore PENDING côté yadony (confirmation perdue, ex.
     * redémarrage ou exception dans l'écouteur) : sans réparation, le bid restait figé pour
     * toujours, la capacité réservée, l'expéditeur débité. Rejoue {@code confirmEscrow} via
     * {@link MobileMoneyBidPaymentService#repairDepositCompletedNotApplied} — idempotente par
     * construction ({@code markEscrowIfPending} ne laisse jamais passer qu'un seul gagnant).
     * L'alerte reste le FILET si la réparation échoue à son tour (deposit ou paiement disparus
     * entre-temps — état structurellement incohérent qui mérite toujours l'œil d'un humain).
     */
    private void repairDepositCompletedNotApplied(UUID bidId) {
        try {
            service.repairDepositCompletedNotApplied(bidId);
        } catch (Exception e) {
            log.error("Réparation du deposit mobile money bloqué pour le bid {} échouée : {}", bidId, e.toString());
            alerts.raiseOnce(DEPOSIT_COMPLETED_ALERT_PREFIX + bidId,
                    "Deposit pawaPay COMPLETED mais paiement encore PENDING pour le bid " + bidId
                            + " — réparation automatique échouée : " + e.getMessage(),
                    Map.of("bidId", bidId.toString()));
        }
    }

    private void evictSearchCache() {
        Cache cache = cacheManager.getCache(SEARCH_CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }
}

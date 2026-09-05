package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
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
 * {@code PawapayReconciliationPoller#reconcile} (tâche 10), dont ce scheduler reprend aussi le
 * garde-fou try/catch par élément.
 *
 * <p>Le lot est borné à {@link #BATCH_SIZE} (même motif, même valeur que
 * {@code PawapayReconciliationPoller}) : un incident qui laisserait s'accumuler des centaines de
 * bids en souffrance ne doit jamais faire durer un seul passage des heures durant, sur l'unique
 * pool de scheduling partagé par tous les crons du dépôt. Triés par
 * {@code awaitingPaymentExpiresAt} croissant : les plus anciens d'abord, la file finit par se
 * vider passage après passage même si le flux entrant ne tarit jamais.
 *
 * <p>Ne dépend pas de {@code yadony.pawapay.enabled} : un bid déjà en vol doit pouvoir expirer
 * même si l'exploitant vient de couper le rail (l'argent, lui, doit revenir au voyageur).
 *
 * <p><b>Ronde 2 (revue)</b> : {@link MobileMoneyBidPaymentService#expire} ne fait plus qu'annuler
 * (ou non) le bid, à l'intérieur de ses deux verrous (paiement, bid) — il ne lève plus d'alerte
 * et n'évince plus le cache lui-même. C'est CE scheduler qui agit sur l'{@code ExpireOutcome}
 * rendu, APRÈS le retour d'{@code expire} (donc après le commit de son {@code REQUIRES_NEW}, hors
 * tout verrou) :
 * <ul>
 *   <li>{@code CANCELLED} → éviction du cache {@value #SEARCH_CACHE_NAME} (même cache, même
 *       éviction totale que {@code AnnouncementSearchBlockEvictionListener}, ici programmatique
 *       via {@link CacheManager} plutôt que déclarative : {@code expire} n'est pas toujours
 *       appelée depuis un contexte où l'annotation aurait un effet utile à chaque retour) ;</li>
 *   <li>{@code PAYMENT_MISSING} / {@code DEPOSIT_COMPLETED_NOT_APPLIED} → alerte administrateur
 *       dédupliquée par bid, structure reprise à l'identique de
 *       {@code PawapayReconciliationPoller#escalateUnknown} : sans cette dédup, un bid resté en
 *       échec fermé (donc resélectionné à chaque tick) spammerait Sentry et Telegram
 *       indéfiniment — le défaut déjà corrigé à la tâche 10, revenu ici avant cette ronde ;</li>
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
    static final String PAYMENT_MISSING_ALERT_PREFIX = "MM_EXPIRE_PAYMENT_MISSING_";
    static final String DEPOSIT_COMPLETED_ALERT_PREFIX = "MM_EXPIRE_DEPOSIT_COMPLETED_";

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPaymentDeadlineScheduler.class);

    private final BidRepository bidRepository;
    private final MobileMoneyBidPaymentService service;
    private final AdminAlertService alerts;
    private final AdminAlertRepository alertRepository;
    private final CacheManager cacheManager;

    public MobileMoneyPaymentDeadlineScheduler(BidRepository bidRepository, MobileMoneyBidPaymentService service,
                                               AdminAlertService alerts, AdminAlertRepository alertRepository,
                                               CacheManager cacheManager) {
        this.bidRepository = bidRepository;
        this.service = service;
        this.alerts = alerts;
        this.alertRepository = alertRepository;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "${yadony.pawapay.deadline-cron}")
    public void expireUnpaidBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BidEntity> due = bidRepository.findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                BidStatus.AWAITING_PAYMENT, PaymentMethod.MOBILE_MONEY, now,
                PageRequest.of(0, BATCH_SIZE, Sort.by("awaitingPaymentExpiresAt").ascending()));
        for (BidEntity bid : due) {
            try {
                handle(bid.getId(), service.expire(bid.getId()));
            } catch (Exception e) {
                // Une expiration en échec (bug, contrainte DB…) ne doit jamais interrompre les
                // autres — même motif que PawapayReconciliationPoller#reconcile.
                log.error("Expiration mobile money du bid {} échouée : {}", bid.getId(), e.toString());
            }
        }
    }

    /** Hors transaction et hors verrou (voir Javadoc de la classe) : agit sur l'issue rendue. */
    private void handle(UUID bidId, MobileMoneyBidPaymentService.ExpireOutcome outcome) {
        switch (outcome) {
            case CANCELLED -> evictSearchCache();
            case PAYMENT_MISSING -> escalate(PAYMENT_MISSING_ALERT_PREFIX + bidId,
                    "Bid " + bidId + " AWAITING_PAYMENT mobile money sans paiement PAWAPAY associé", bidId);
            case DEPOSIT_COMPLETED_NOT_APPLIED -> escalate(DEPOSIT_COMPLETED_ALERT_PREFIX + bidId,
                    "Deposit pawaPay COMPLETED mais paiement encore PENDING pour le bid " + bidId, bidId);
            case IGNORED -> { }
        }
    }

    private void evictSearchCache() {
        Cache cache = cacheManager.getCache(SEARCH_CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * Structure reprise à l'identique de {@code PawapayReconciliationPoller#escalateUnknown} :
     * {@code type} porte l'identifiant du bid, donc unique par incident — dédupliqué en
     * cherchant une alerte non résolue du même type AVANT d'en créer une nouvelle et d'appeler
     * {@link AdminAlertService#raise}. Sans cette dédup, un bid en échec fermé (resélectionné à
     * chaque tick tant qu'il n'est pas résolu) déclencherait cet appel — synchrone, HTTP
     * Telegram — indéfiniment, une fois par minute.
     */
    private void escalate(String type, String message, UUID bidId) {
        if (!alertRepository.findByTypeAndResolved(type, false).isEmpty()) {
            return;
        }
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType(type);
        alert.setPayload("{\"bidId\":\"" + bidId + "\"}");
        alert.setResolved(false);
        alertRepository.save(alert);
        alerts.raise(type, message, Map.of("bidId", bidId.toString()));
    }
}

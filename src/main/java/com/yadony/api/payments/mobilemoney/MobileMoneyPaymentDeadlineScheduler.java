package com.yadony.api.payments.mobilemoney;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class MobileMoneyPaymentDeadlineScheduler {

    /**
     * Borne un passage — sans elle, un incident prolongé (paiement bloqué en masse) pourrait
     * accumuler des centaines de bids en souffrance et faire durer une exécution des heures
     * durant, sur l'unique pool de scheduling partagé par tous les crons du dépôt.
     */
    static final int BATCH_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPaymentDeadlineScheduler.class);

    private final BidRepository bidRepository;
    private final MobileMoneyBidPaymentService service;

    public MobileMoneyPaymentDeadlineScheduler(BidRepository bidRepository, MobileMoneyBidPaymentService service) {
        this.bidRepository = bidRepository;
        this.service = service;
    }

    @Scheduled(cron = "${yadony.pawapay.deadline-cron}")
    public void expireUnpaidBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BidEntity> due = bidRepository.findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                BidStatus.AWAITING_PAYMENT, PaymentMethod.MOBILE_MONEY, now,
                PageRequest.of(0, BATCH_SIZE, Sort.by("awaitingPaymentExpiresAt").ascending()));
        for (BidEntity bid : due) {
            try {
                service.expire(bid.getId());
            } catch (Exception e) {
                // Une expiration en échec (bug, contrainte DB…) ne doit jamais interrompre les
                // autres — même motif que PawapayReconciliationPoller#reconcile.
                log.error("Expiration mobile money du bid {} échouée : {}", bid.getId(), e.toString());
            }
        }
    }
}

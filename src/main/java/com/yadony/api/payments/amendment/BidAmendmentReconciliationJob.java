package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Filet planifié des modifications de colis, toutes les {@code reconcile-interval} :
 * <ol>
 *   <li>expire les modifications dont l'étape en cours est échue ({@link BidAmendmentWindow#openUntil}) ;</li>
 *   <li>rappelle au voyageur, une seule fois, une modification qui attend sa réponse à {@code reminder-before} de
 *       l'échéance ;</li>
 *   <li>clôt ou rembourse les modifications d'un colis terminé sans livraison dont l'événement de fin s'est perdu
 *       (grâce de {@link #BID_ENDED_GRACE}), et rend leurs kilos ;</li>
 *   <li>rend la commission espèces d'une modification close quand la suite après commit a échoué ou s'est perdue ;</li>
 *   <li>rejoue le remboursement des PaymentIntents de commission abouties hors de l'essai courant (B5), à partir de
 *       l'audit {@code BID_AMENDMENT_STALE_COMMISSION} ;</li>
 *   <li>signale à l'administration une modification acquise ni versée ni remboursée {@link #UNSETTLED_DELAY} après
 *       la fin du colis.</li>
 * </ol>
 * Chaque élément a sa propre transaction ; un élément en échec n'arrête pas le lot. Instance unique, pas de verrou
 * distribué (comme le filet du supplément). Les versements et remboursements carte et mobile money manqués sont
 * repris par les filets de leurs rails (B10, B11).
 */
@Component
public class BidAmendmentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentReconciliationJob.class);

    static final int BATCH_SIZE = 100;

    /** Un colis terminé, ou une modification espèces close, depuis moins longtemps est laissé à son événement. */
    static final Duration BID_ENDED_GRACE = Duration.ofMinutes(15);

    /** Délai avant l'alerte « modification non soldée » après la fin du colis. */
    static final Duration UNSETTLED_DELAY = Duration.ofDays(3);

    /**
     * Fenêtre du rejeu des remboursements hors essai : au-delà, l'audit n'est plus balayé (il grossit sans fin et le
     * filet ne doit pas rejouer indéfiniment un Refund refusé). Une PaymentIntent plus ancienne encore non rendue reste
     * signalée par l'alerte {@code AMEND_STALE_COMM_} levée au constat, à traiter à la main ; 30 jours couvrent
     * largement une panne de Stripe ou du filet, et restent loin des 180 jours au-delà desquels Stripe refuse un Refund.
     */
    static final Duration STALE_REFUND_WINDOW = Duration.ofDays(30);

    /**
     * Échecs du Refund d'une même PaymentIntent hors essai ({@code BID_AMENDMENT_STALE_COMMISSION_REFUND_FAILED}) au-delà
     * desquels le filet cesse de la rejouer, une fois {@link #STALE_REFUND_GIVE_UP_AFTER} écoulé depuis le premier :
     * l'alerte {@code AMEND_STALE_REFUND_FAIL_} reste, à traiter à la main.
     */
    static final int STALE_REFUND_MAX_FAILURES = 3;

    /**
     * Durée que les échecs doivent couvrir avant l'abandon (premier échec plus ancien que cela) : les erreurs
     * passagères (réseau, 5xx, limite de débit Stripe) sont rejouées à chaque passage pendant au moins 24 h.
     */
    static final Duration STALE_REFUND_GIVE_UP_AFTER = Duration.ofHours(24);

    /** Préfixe de l'alerte ; avec un UUID, 56 caractères ({@code AdminAlertTypeLengthTest}). */
    static final String UNSETTLED_ALERT_PREFIX = "AMENDMENT_UNSETTLED_";

    /** Statuts qu'une fin de colis doit encore traiter : ouverts, ou acquis et non versés. */
    static final Set<BidAmendmentStatus> ENDED_BID_STATUSES = EnumSet.of(BidAmendmentStatus.AUTHORIZATION_PENDING,
            BidAmendmentStatus.PROPOSED, BidAmendmentStatus.COMMISSION_PENDING, BidAmendmentStatus.PAYMENT_PENDING,
            BidAmendmentStatus.ACCEPTED);

    /** Modifications espèces closes dont la commission peut rester à rendre. */
    static final Set<BidAmendmentStatus> CASH_CLOSED = EnumSet.of(BidAmendmentStatus.DECLINED,
            BidAmendmentStatus.EXPIRED, BidAmendmentStatus.CANCELLED, BidAmendmentStatus.REFUNDED);

    /** Carte ou mobile money : ni versées ni remboursées. */
    static final Set<BidAmendmentStatus> UNSETTLED_ONLINE = EnumSet.of(BidAmendmentStatus.ACCEPTED,
            BidAmendmentStatus.PAYOUT_PENDING, BidAmendmentStatus.REFUND_PENDING);

    /** Colis terminés : livrés, ou terminés sans livraison. */
    static final Set<BidStatus> ENDED_BIDS;

    static {
        EnumSet<BidStatus> ended = EnumSet.of(BidStatus.COMPLETED);
        ended.addAll(BidAmendmentService.TERMINAL_UNDELIVERED);
        ENDED_BIDS = Set.copyOf(ended);
    }

    private final BidAmendmentRepository amendments;
    private final BidAmendmentService service;
    private final BidAmendmentCashRefunds cashRefunds;
    private final BidAmendmentWindow window;
    private final BidAmendmentProperties properties;
    private final AdminAlertEscalator alerts;

    public BidAmendmentReconciliationJob(BidAmendmentRepository amendments, BidAmendmentService service,
                                         BidAmendmentCashRefunds cashRefunds, BidAmendmentWindow window,
                                         BidAmendmentProperties properties, AdminAlertEscalator alerts) {
        this.amendments = amendments;
        this.service = service;
        this.cashRefunds = cashRefunds;
        this.window = window;
        this.properties = properties;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${yadony.bid-amendment.reconcile-interval:PT10M}", initialDelayString = "PT2M")
    public void scheduledRun() {
        if (properties.isReconcileEnabled()) {
            run();
        }
    }

    /** Résultat d'un passage, pour les logs et les tests. */
    public record Outcome(int expired, int reminded, int ended, int cashSettled, int staleRefunded, int unsettled) {
    }

    public Outcome run() {
        LocalDateTime now = window.now();
        PageRequest batch = PageRequest.of(0, BATCH_SIZE);

        int expired = 0;
        for (UUID id : amendments.findIdsLapsed(now, now.minus(properties.getAuthorizationWindow()),
                now.minus(properties.getCommissionConfirmWindow()), batch)) {
            try {
                if (service.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Expiration de la modification {} en échec", id, e);
            }
        }

        int reminded = 0;
        for (UUID id : amendments.findIdsReminderDue(now, properties.getReminderBefore().toSeconds(), BATCH_SIZE)) {
            try {
                if (service.remindTraveler(id)) {
                    reminded++;
                }
            } catch (RuntimeException e) {
                log.error("Rappel de la modification {} en échec", id, e);
            }
        }

        int ended = 0;
        for (UUID bidId : amendments.findBidIdsEndedWith(ENDED_BID_STATUSES, BidAmendmentService.TERMINAL_UNDELIVERED,
                now.minus(BID_ENDED_GRACE), batch)) {
            try {
                if (service.onBidEnded(bidId, "BID_ENDED")) {
                    ended++;
                }
            } catch (RuntimeException e) {
                log.error("Fin des modifications du colis terminé {} en échec", bidId, e);
            }
        }

        int cashSettled = 0;
        for (UUID id : amendments.findCashIdsToSettle(CASH_CLOSED, now.minus(BID_ENDED_GRACE), batch)) {
            try {
                if (cashRefunds.settle(id)) {
                    cashSettled++;
                }
            } catch (RuntimeException e) {
                log.error("Commission de la modification espèces {} : remboursement en échec", id, e);
            }
        }

        int staleRefunded = 0;
        for (Object[] row : amendments.findStaleCommissionsWithoutRefund(now.minus(STALE_REFUND_WINDOW),
                STALE_REFUND_MAX_FAILURES, now.minus(STALE_REFUND_GIVE_UP_AFTER), BATCH_SIZE)) {
            String piId = String.valueOf(row[2]);
            try {
                // Idempotent par clé de PaymentIntent : un Refund déjà créé chez Stripe est retrouvé, rien n'est recréé.
                if (cashRefunds.refundStale(UUID.fromString(String.valueOf(row[0])),
                        UUID.fromString(String.valueOf(row[1])), piId)) {
                    staleRefunded++;
                }
            } catch (RuntimeException e) {
                log.error("Remboursement de la PaymentIntent de commission hors essai {} en échec", piId, e);
            }
        }

        int unsettled = 0;
        for (UUID id : amendments.findIdsUnsettledAfterBidEnd(UNSETTLED_ONLINE, ENDED_BIDS,
                BidAmendmentService.TERMINAL_UNDELIVERED, UNSETTLED_ALERT_PREFIX, now.minus(UNSETTLED_DELAY), batch)) {
            try {
                if (raiseUnsettled(id)) {
                    unsettled++;
                }
            } catch (RuntimeException e) {
                log.error("Alerte de la modification non soldée {} en échec", id, e);
            }
        }

        Outcome outcome = new Outcome(expired, reminded, ended, cashSettled, staleRefunded, unsettled);
        if (expired > 0 || reminded > 0 || ended > 0 || cashSettled > 0 || staleRefunded > 0 || unsettled > 0) {
            log.info("Filet des modifications de colis : {}", outcome);
        }
        return outcome;
    }

    /** Alerte « modification non soldée », levée une fois tant qu'elle n'est pas résolue ({@link AdminAlertEscalator#raiseOnce}). */
    private boolean raiseUnsettled(UUID amendmentId) {
        BidAmendmentEntity a = amendments.findById(amendmentId).orElse(null);
        if (a == null || !(a.getRail() == BidSupplementRail.CASH
                ? a.getStatus() == BidAmendmentStatus.ACCEPTED : UNSETTLED_ONLINE.contains(a.getStatus()))) {
            return false;
        }
        // Brut seulement : l'alerte reprend ce que l'audit trace, jamais le net du voyageur.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("amendmentId", a.getId().toString());
        context.put("bidId", a.getBidId().toString());
        context.put("rail", a.getRail().name());
        context.put("status", a.getStatus().name());
        context.put("gross", a.getGrossAmount().toPlainString());
        context.put("currency", a.getCurrency());
        context.put("acceptedAt", String.valueOf(a.getAcceptedAt()));
        return alerts.raiseOnce(UNSETTLED_ALERT_PREFIX + a.getId(), "WARN",
                "Modification " + a.getId() + " du colis " + a.getBidId() + " toujours " + a.getStatus() + " ("
                        + a.getGrossAmount().toPlainString() + " " + a.getCurrency() + ") plus de 3 jours après la fin "
                        + "du colis : ni versée au voyageur ni remboursée à l'expéditeur, à traiter à la main",
                context);
    }
}

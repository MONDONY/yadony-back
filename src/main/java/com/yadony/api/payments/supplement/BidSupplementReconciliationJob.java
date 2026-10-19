package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Filet planifié des suppléments (plan §5.2, points 1, 2 et 4) : expire les demandes échues, annule
 * celles d'un colis terminé sans livraison dont l'événement aurait été perdu, demande le
 * remboursement d'un supplément payé sur un tel colis (le rail le rend), et signale à
 * l'administration un supplément resté payé {@link #UNSETTLED_DELAY} après que le principal a été
 * tranché autrement que par un versement (partage admin d'un litige, principal remboursé pendant un
 * litige) : décision manuelle (décision n° 3 du propriétaire), par
 * {@code POST /admin/supplements/{id}/payout|refund}. Les versements et remboursements manqués sont
 * repris par les filets des rails. Chaque élément a sa propre transaction ; un élément en échec
 * n'arrête pas le lot. Instance unique, pas de verrou distribué (comme
 * {@code StripeReleaseRecoveryJob}).
 */
@Component
public class BidSupplementReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementReconciliationJob.class);

    static final int BATCH_SIZE = 100;

    /** Un colis terminé depuis moins longtemps est laissé à son événement de fin. */
    static final Duration BID_ENDED_GRACE = Duration.ofMinutes(15);

    /** Statuts d'une demande que la fin du colis doit encore traiter. */
    /** Délai avant l'alerte « supplément non soldé » (plan §5.2 point 4 : J+3). */
    static final Duration UNSETTLED_DELAY = Duration.ofDays(3);

    /** Préfixe de l'alerte ; avec un UUID, 51 caractères ({@code AdminAlertTypeLengthTest}). */
    static final String UNSETTLED_ALERT_PREFIX = "SUPP_UNSETTLED_";

    static final java.util.Set<BidSupplementStatus> ENDED_BID_STATUSES = java.util.EnumSet.of(
            BidSupplementStatus.COMMISSION_PENDING, BidSupplementStatus.PROPOSED, BidSupplementStatus.PAYMENT_PENDING,
            BidSupplementStatus.PAID);

    private final BidSupplementRepository supplements;
    private final BidSupplementService service;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;
    private final AdminAlertEscalator alerts;

    public BidSupplementReconciliationJob(BidSupplementRepository supplements, BidSupplementService service,
                                          BidSupplementWindow window, BidSupplementProperties properties,
                                          AdminAlertEscalator alerts) {
        this.supplements = supplements;
        this.service = service;
        this.window = window;
        this.properties = properties;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${yadony.bid-supplement.reconcile-interval:PT10M}", initialDelayString = "PT2M")
    public void scheduledRun() {
        if (properties.isReconcileEnabled()) {
            run();
        }
    }

    /** Résultat d'un passage, pour les logs et les tests. */
    public record Outcome(int expired, int cancelled, int unsettled) {
    }

    public Outcome run() {
        int expired = 0;
        List<UUID> due = supplements.findIdsOpenExpiredBefore(BidSupplementStatus.OPEN, window.now(),
                PageRequest.of(0, BATCH_SIZE));
        for (UUID id : due) {
            try {
                if (service.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Expiration du supplément {} en échec", id, e);
            }
        }

        int cancelled = 0;
        // Ouvertes (à annuler) ou payées (à rembourser, rail carte ou mobile money) : un PAID resté
        // sur un colis mort après un événement de fin perdu.
        List<UUID> ended = supplements.findBidIdsEndedWithSupplementIn(ENDED_BID_STATUSES,
                BidSupplementService.TERMINAL_UNDELIVERED, window.now().minus(BID_ENDED_GRACE),
                PageRequest.of(0, BATCH_SIZE));
        for (UUID bidId : ended) {
            try {
                if (service.onBidEnded(bidId, "BID_ENDED")) {
                    cancelled++;
                }
            } catch (RuntimeException e) {
                log.error("Annulation du supplément du colis terminé {} en échec", bidId, e);
            }
        }
        int unsettled = 0;
        for (UUID id : supplements.findIdsUnsettledAfterPrincipalDecision(window.now().minus(UNSETTLED_DELAY),
                PageRequest.of(0, BATCH_SIZE))) {
            try {
                if (raiseUnsettled(id)) {
                    unsettled++;
                }
            } catch (RuntimeException e) {
                log.error("Alerte du supplément non soldé {} en échec", id, e);
            }
        }
        if (expired > 0 || cancelled > 0 || unsettled > 0) {
            log.info("Filet des suppléments : {} expiré(s), {} annulé(s) sur colis terminé, {} non soldé(s) signalé(s)",
                    expired, cancelled, unsettled);
        }
        return new Outcome(expired, cancelled, unsettled);
    }

    /**
     * Alerte « supplément bloqué par une décision sur le principal » : levée une fois tant qu'elle
     * n'est pas résolue ({@link AdminAlertEscalator#raiseOnce}).
     */
    private boolean raiseUnsettled(UUID supplementId) {
        BidSupplementEntity s = supplements.findById(supplementId).orElse(null);
        if (s == null || s.getStatus() != BidSupplementStatus.PAID) {
            return false;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("supplementId", s.getId().toString());
        context.put("bidId", s.getBidId().toString());
        context.put("rail", s.getRail().name());
        context.put("gross", s.getGrossAmount().toPlainString());
        context.put("net", s.getNetAmount().toPlainString());
        context.put("currency", s.getCurrency());
        context.put("paidAt", String.valueOf(s.getPaidAt()));
        context.put("actions", "POST /admin/supplements/" + s.getId() + "/payout ou /refund");
        return alerts.raiseOnce(UNSETTLED_ALERT_PREFIX + s.getId(), "WARN",
                "Supplément " + s.getId() + " du colis " + s.getBidId() + " toujours payé ("
                        + s.getGrossAmount().toPlainString() + " " + s.getCurrency() + ") alors que le paiement du "
                        + "colis a été tranché sans versement (partage de litige ou remboursement) : verser au voyageur "
                        + "ou rembourser l'expéditeur, décision manuelle",
                context);
    }
}

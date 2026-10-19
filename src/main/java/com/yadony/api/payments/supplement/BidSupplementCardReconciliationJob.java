package com.yadony.api.payments.supplement;

import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.StripeReleaseIntentEntity;
import com.yadony.api.payments.StripeReleaseIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Filet planifié du rail carte (plan §5.2, carte) : remboursements restés en suspens, paiements
 * tardifs pas encore rendus, versements bloqués en {@code PAYOUT_PENDING}, versements manqués
 * (principal versé, supplément resté {@code PAID}). Chaque élément a ses propres transactions
 * courtes et ses appels Stripe hors transaction ; un échec n'arrête pas le lot. Les demandes
 * échues et les colis terminés sont traités par {@link BidSupplementReconciliationJob}.
 */
@Component
public class BidSupplementCardReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCardReconciliationJob.class);

    static final int BATCH_SIZE = 100;

    /** Un état plus récent est laissé à son déroulement normal (webhook, écouteur). */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(10);

    /** Délai laissé à {@code PaymentReleasedEvent} avant de verser le supplément à sa place. */
    static final Duration MISSED_PAYOUT_GRACE = Duration.ofMinutes(30);

    private final BidSupplementRepository supplements;
    private final BidSupplementCardPayouts payouts;
    private final BidSupplementCardRefunds refunds;
    private final PaymentRepository payments;
    private final StripeReleaseIntentRepository releaseIntents;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;

    public BidSupplementCardReconciliationJob(BidSupplementRepository supplements, BidSupplementCardPayouts payouts,
                                              BidSupplementCardRefunds refunds, PaymentRepository payments,
                                              StripeReleaseIntentRepository releaseIntents,
                                              BidSupplementWindow window, BidSupplementProperties properties) {
        this.supplements = supplements;
        this.payouts = payouts;
        this.refunds = refunds;
        this.payments = payments;
        this.releaseIntents = releaseIntents;
        this.window = window;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yadony.bid-supplement.reconcile-interval:PT10M}", initialDelayString = "PT3M")
    public void scheduledRun() {
        if (properties.isReconcileEnabled()) {
            run();
        }
    }

    /** Résultat d'un passage, pour les logs et les tests. */
    public record Outcome(int refunded, int paidOut) {
    }

    public Outcome run() {
        LocalDateTime now = window.now();
        LocalDateTime settled = now.minus(SETTLE_GRACE);
        PageRequest page = PageRequest.of(0, BATCH_SIZE);

        int refunded = each(supplements.findIdsByRailAndStatusInUpdatedBefore(BidSupplementRail.STRIPE,
                EnumSet.of(BidSupplementStatus.REFUND_PENDING), settled, page), refunds::refund, "remboursement");
        refunded += each(supplements.findIdsPaidLateUnrefunded(BidSupplementRail.STRIPE,
                BidSupplementCardLedger.CLOSED_UNPAID, settled, page), refunds::refund, "paiement tardif");

        int paidOut = each(supplements.findIdsByRailAndStatusInUpdatedBefore(BidSupplementRail.STRIPE,
                EnumSet.of(BidSupplementStatus.PAYOUT_PENDING), settled, page), payouts::resume, "reprise du versement");
        paidOut += each(supplements.findIdsByRailAndStatusPaidBefore(BidSupplementRail.STRIPE,
                BidSupplementStatus.PAID, now.minus(MISSED_PAYOUT_GRACE), page),
                id -> principalReleased(id) && payouts.payout(id), "versement manqué");

        if (refunded > 0 || paidOut > 0) {
            log.info("Filet des suppléments carte : {} remboursé(s), {} versé(s)", refunded, paidOut);
        }
        return new Outcome(refunded, paidOut);
    }

    /**
     * Le versement principal du colis a-t-il abouti par l'exécuteur des versements (réservation
     * {@code DONE}, c'est-à-dire {@code PaymentReleasedEvent} publié) ? Un partage admin d'un
     * litige n'y passe pas : le supplément reste payé, décision manuelle (décision n° 3).
     */
    private boolean principalReleased(UUID supplementId) {
        return supplements.findBidIdById(supplementId)
                .flatMap(payments::findForBid)
                .filter(p -> p.getStatus() == PaymentStatus.RELEASED)
                .map(PaymentEntity::getId)
                .flatMap(releaseIntents::findByPaymentId)
                .map(intent -> intent.getStatus() == StripeReleaseIntentEntity.Status.DONE)
                .orElse(false);
    }

    private static int each(List<UUID> ids, Predicate<UUID> action, String label) {
        int done = 0;
        for (UUID id : ids) {
            try {
                if (action.test(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("Filet des suppléments carte : {} du supplément {} en échec", label, id, e);
            }
        }
        return done;
    }
}

package com.yadony.api.payments.supplement;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Filet planifié du rail mobile money (plan §5.2, mobile money) : dépôts aboutis jamais appliqués
 * (écouteur en échec : la demande serait restée en attente, sans jamais expirer), remboursements en suspens
 * ({@code REFUND_PENDING}, paiements tardifs pas encore rendus), versements restés
 * {@code PAYOUT_PENDING} (opération relue, sinon soumise), versements manqués (payout principal
 * abouti, supplément resté {@code PAID}, sélectionnés en base par
 * {@link BidSupplementRepository#findIdsAwaitingMissedMobileMoneyPayout}). Une opération pawaPay
 * encore en vol n'est jamais doublée : chaque reprise relit d'abord l'opération du supplément. Les
 * demandes échues et les colis terminés sont traités par {@link BidSupplementReconciliationJob}.
 */
@Component
public class BidSupplementMobileMoneyReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementMobileMoneyReconciliationJob.class);

    static final int BATCH_SIZE = 100;

    /** Un état plus récent est laissé à son déroulement normal (callback, poller, écouteur). */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(10);

    /** Délai laissé à {@code PaymentReleasedEvent} avant de verser le supplément à sa place. */
    static final Duration MISSED_PAYOUT_GRACE = Duration.ofMinutes(30);

    private final BidSupplementRepository supplements;
    private final BidSupplementMobileMoneyLedger ledger;
    private final PawapayOperationService operations;
    private final BidSupplementMobileMoneyPayouts payouts;
    private final BidSupplementMobileMoneyRefunds refunds;
    private final BidSupplementWindow window;
    private final BidSupplementProperties properties;

    public BidSupplementMobileMoneyReconciliationJob(BidSupplementRepository supplements,
                                                     BidSupplementMobileMoneyLedger ledger,
                                                     PawapayOperationService operations,
                                                     BidSupplementMobileMoneyPayouts payouts,
                                                     BidSupplementMobileMoneyRefunds refunds,
                                                     BidSupplementWindow window, BidSupplementProperties properties) {
        this.supplements = supplements;
        this.ledger = ledger;
        this.operations = operations;
        this.payouts = payouts;
        this.refunds = refunds;
        this.window = window;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yadony.bid-supplement.reconcile-interval:PT10M}", initialDelayString = "PT4M")
    public void scheduledRun() {
        if (properties.isReconcileEnabled()) {
            run();
        }
    }

    /** Résultat d'un passage, pour les logs et les tests. */
    public record Outcome(int deposits, int refunds, int payouts) {
    }

    /** Statuts où un dépôt abouti reste à appliquer : en attente de paiement, ou clos sans paiement. */
    static final Set<BidSupplementStatus> UNAPPLIED_DEPOSIT_STATUSES = EnumSet.of(BidSupplementStatus.PAYMENT_PENDING,
            BidSupplementStatus.DECLINED, BidSupplementStatus.EXPIRED, BidSupplementStatus.WITHDRAWN,
            BidSupplementStatus.CANCELLED);

    public Outcome run() {
        LocalDateTime now = window.now();
        LocalDateTime settled = now.minus(SETTLE_GRACE);
        PageRequest page = PageRequest.of(0, BATCH_SIZE);

        int deposits = each(supplements.findIdsWithUnappliedMobileMoneyDeposit(UNAPPLIED_DEPOSIT_STATUSES, settled,
                page), this::applyDeposit, "dépôt non appliqué");

        int refunded = each(supplements.findIdsByRailAndStatusInUpdatedBefore(BidSupplementRail.PAWAPAY,
                EnumSet.of(BidSupplementStatus.REFUND_PENDING), settled, page), refunds::refund, "remboursement");
        refunded += each(supplements.findIdsPaidLateUnrefunded(BidSupplementRail.PAWAPAY,
                BidSupplementCardLedger.CLOSED_UNPAID, settled, page), refunds::refund, "paiement tardif");

        int paidOut = each(supplements.findIdsByRailAndStatusInUpdatedBefore(BidSupplementRail.PAWAPAY,
                EnumSet.of(BidSupplementStatus.PAYOUT_PENDING), settled, page), payouts::resume, "reprise du versement");
        paidOut += each(supplements.findIdsAwaitingMissedMobileMoneyPayout(now.minus(MISSED_PAYOUT_GRACE), page),
                payouts::payout, "versement manqué");

        if (deposits > 0 || refunded > 0 || paidOut > 0) {
            log.info("Filet des suppléments mobile money : {} dépôt(s) appliqué(s), {} remboursement(s), {} versement(s)",
                    deposits, refunded, paidOut);
        }
        return new Outcome(deposits, refunded, paidOut);
    }

    /** Dépôt abouti dont l'issue n'a jamais été appliquée : payé, ou paiement tardif à rendre. */
    private boolean applyDeposit(UUID supplementId) {
        return operations.findLiveForSupplement(supplementId, PawapayOperationKind.DEPOSIT)
                .filter(op -> op.getStatus() == PawapayOperationStatus.COMPLETED)
                .map(op -> {
                    BidSupplementCardLedger.PaidOutcome outcome = ledger.markPaid(supplementId, op.getId(), "reconcile");
                    return outcome == BidSupplementCardLedger.PaidOutcome.PAID
                            || outcome == BidSupplementCardLedger.PaidOutcome.LATE_REFUND;
                })
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
                log.error("Filet des suppléments mobile money : {} du supplément {} en échec", label, id, e);
            }
        }
        return done;
    }
}

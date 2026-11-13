package com.yadony.api.payments;

import com.yadony.api.cancellation.DepositPayoutPort;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.amendment.BidAmendmentRepository;
import com.yadony.api.payments.amendment.BidAmendmentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Réponse du paquet {@code payments} au port {@link DepositPayoutPort} : la plateforme a-t-elle réellement versé de
 * l'argent au transporteur pour ce colis payé au dépôt ?
 *
 * <ul>
 *   <li>principal carte ou mobile money (paiement du colis, ou du fil de négociation dont il est issu) en
 *       {@code RELEASED} : le claim atomique ESCROW → RELEASED précède le Transfer ou le payout, RELEASED veut donc
 *       dire « parti ou en partance ». Un principal en espèces n'est jamais regardé (aucun argent plateforme) ;</li>
 *   <li>ou une modification du colis payée en ligne (carte, mobile money) en cours de versement ou versée : celles d'un
 *       colis payé au dépôt partent au dépôt.</li>
 * </ul>
 *
 * <p>Verrou : dans la transaction de l'appelant, qui tient déjà le verrou du colis. Un paiement encore en séquestre est
 * verrouillé ({@link PaymentRepository#lockIfEscrow}) : un claim de versement concurrent pas encore commité est attendu,
 * puis le statut est relu en base (même mécanisme que l'annulation admin). Jamais de décision sur un statut en cache.
 */
@Component
public class DepositPayoutAdapter implements DepositPayoutPort {

    /** Versement d'une modification parti ou en partance. */
    static final Set<BidAmendmentStatus> AMENDMENT_PAID_OUT =
            EnumSet.of(BidAmendmentStatus.PAYOUT_PENDING, BidAmendmentStatus.PAID_OUT);

    private final BidRepository bids;
    private final PaymentRepository payments;
    private final BidAmendmentRepository amendments;

    public DepositPayoutAdapter(BidRepository bids, PaymentRepository payments, BidAmendmentRepository amendments) {
        this.bids = bids;
        this.payments = payments;
        this.amendments = amendments;
    }

    @Override
    public boolean paidAtDeposit(UUID bidId) {
        BidEntity bid = bids.findById(bidId).orElse(null);
        if (bid == null || bid.getPayoutTrigger() != PayoutTrigger.DEPOSIT) {
            return false;
        }
        if (bid.getPaymentMethod() != PaymentMethod.CASH && principalReleased(bidId)) {
            return true;
        }
        return amendments.existsByBidIdAndRailNotAndStatusIn(bidId, BidSupplementRail.CASH, AMENDMENT_PAID_OUT);
    }

    private boolean principalReleased(UUID bidId) {
        Optional<PaymentEntity> payment = payments.findForBid(bidId);
        if (payment.isEmpty()) {
            return false;
        }
        UUID paymentId = payment.get().getId();
        payments.lockIfEscrow(paymentId);
        PaymentStatus status = payments.findStatusById(paymentId).orElse(payment.get().getStatus());
        return status == PaymentStatus.RELEASED;
    }
}

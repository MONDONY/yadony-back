package com.yadony.api.payments.amendment;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Déclencheurs du versement des modifications acquises d'un colis ({@link BidAmendmentPayouts#payoutForBid}), après le
 * commit qui les décide et hors de toute transaction (appel Stripe) :
 * <ul>
 *   <li>{@link PaymentReleasedEvent}, quel que soit le rail du principal (carte ou mobile money) : le voyageur vient
 *       d'être payé du principal, la modification suit ;</li>
 *   <li>{@link DeliveryConfirmedEvent} quand le principal est payé en espèces : aucun versement principal n'est alors
 *       publié. Un principal carte ou mobile money attend son propre versement (retenue, litige), jamais la seule
 *       livraison.</li>
 * </ul>
 * Un rejeu ne trouve plus de modification {@code ACCEPTED} et reste sans effet ; un événement perdu est rattrapé par le
 * filet ({@link BidAmendmentReconciliationJob}, versement manqué).
 */
@Component
public class BidAmendmentPayoutListener {

    private final BidAmendmentPayouts payouts;
    private final BidRepository bids;

    public BidAmendmentPayoutListener(BidAmendmentPayouts payouts, BidRepository bids) {
        this.payouts = payouts;
        this.bids = bids;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPaymentReleased(PaymentReleasedEvent e) {
        payouts.payoutForBid(e.getBidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent e) {
        if (bids.findById(e.getBidId()).map(BidEntity::getPaymentMethod).orElse(null) != PaymentMethod.CASH) {
            return;
        }
        payouts.payoutForBid(e.getBidId());
    }
}

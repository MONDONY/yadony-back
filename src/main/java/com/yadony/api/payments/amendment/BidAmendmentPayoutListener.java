package com.yadony.api.payments.amendment;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Déclencheurs du versement des modifications acquises d'un colis ({@link BidAmendmentPayouts#payoutForBid}), après le
 * commit qui les décide et hors de toute transaction (appel Stripe) :
 * <ul>
 *   <li>{@link PaymentReleasedEvent}, quel que soit le rail du principal (carte ou mobile money) : le voyageur vient
 *       d'être payé du principal, la modification suit. C'est aussi le cas d'un colis payé au dépôt
 *       ({@link PayoutTrigger#DEPOSIT}) : son principal est versé au dépôt, la modification avec lui ;</li>
 *   <li>principal payé en espèces : aucun versement principal n'est publié. La modification part au moment où le
 *       principal aurait été versé : à la livraison ({@link DeliveryConfirmedEvent}) pour un colis payé à la
 *       livraison, au dépôt ({@link ParcelDepositedEvent}) pour un colis payé au dépôt, jamais aux deux. Un principal
 *       carte ou mobile money attend son propre versement (retenue, litige), jamais la seule livraison ni le seul
 *       dépôt.</li>
 * </ul>
 * Un rejeu ne trouve plus de modification {@code ACCEPTED} et reste sans effet ; un événement perdu (ou un versement
 * retenu) est rattrapé par le filet ({@link BidAmendmentReconciliationJob}, versement manqué, colis livré).
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

    /** Principal espèces payé à la livraison : ses modifications partent à la livraison. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent e) {
        BidEntity bid = bids.findById(e.getBidId()).orElse(null);
        if (!cashPrincipal(bid) || bid.getPayoutTrigger() == PayoutTrigger.DEPOSIT) {
            return;
        }
        payouts.payoutForBid(e.getBidId());
    }

    /**
     * Principal espèces payé au dépôt : ses modifications (carte) partent au dépôt du colis, comme le principal d'un
     * colis carte ou mobile money payé au dépôt. Le dépôt n'est publié que pour un colis {@link PayoutTrigger#DEPOSIT}
     * (règle revérifiée ici).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onParcelDeposited(ParcelDepositedEvent e) {
        BidEntity bid = bids.findById(e.bidId()).orElse(null);
        if (!cashPrincipal(bid) || bid.getPayoutTrigger() != PayoutTrigger.DEPOSIT) {
            return;
        }
        payouts.payoutForBid(e.bidId());
    }

    private static boolean cashPrincipal(BidEntity bid) {
        return bid != null && bid.getPaymentMethod() == PaymentMethod.CASH;
    }
}

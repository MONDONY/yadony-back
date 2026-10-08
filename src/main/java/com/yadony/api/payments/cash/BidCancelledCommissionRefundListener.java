package com.yadony.api.payments.cash;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Rembourse la commission Yadony lorsqu'un bid CASH est annulé individuellement
 * (avant remise) via {@code BidService.cancelBid} — qui publie {@link BidRejectedEvent}
 * (reason CANCELLED_BY_SENDER / CANCELLED_BY_TRAVELER).
 *
 * <p>Sans ce listener, seule l'annulation de TRAJET ({@code TripCancelledEvent} →
 * Wallet/CardCommissionTripCancelRefundListener) et le SENDER_NO_SHOW
 * ({@code CancellationConfirmedEvent} → CommissionRefundListener) remboursaient la
 * commission : un bid cash accepté puis annulé laissait la commission prélevée au
 * voyageur (wallet débité) sans restitution.
 *
 * <p>Route selon commissionChargedVia :
 * <ul>
 *   <li>WALLET → crédit wallet (clé idempotente {@code wallet-refund-cancel-{bidId}}) ;</li>
 *   <li>CARD   → refund Stripe.</li>
 * </ul>
 * Gardes : ne traite que les bids CASH dont {@code commissionStatus == CHARGED}
 * (refundCommissionToWallet/refundCommission verrouillent en plus la transition
 * → REFUNDED, prévenant tout double remboursement avec les autres flux).
 */
@Component
public class BidCancelledCommissionRefundListener {

    private static final Logger log = LoggerFactory.getLogger(BidCancelledCommissionRefundListener.class);

    private final CashCommissionService cashCommissionService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;

    public BidCancelledCommissionRefundListener(CashCommissionService cashCommissionService,
                                                BidRepository bidRepository,
                                                AnnouncementRepository announcementRepository) {
        this.cashCommissionService = cashCommissionService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidRejected(BidRejectedEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) return;
        if (bid.getPaymentMethod() != PaymentMethod.CASH) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) return;

        CommissionChargedVia via = bid.getCommissionChargedVia();
        if (via == null) {
            log.warn("BidCancelledCommissionRefundListener: bid {} CHARGED mais commissionChargedVia=null"
                    + " — remboursement impossible", bid.getId());
            return;
        }

        switch (via) {
            case WALLET -> {
                AnnouncementEntity announcement =
                        announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
                if (announcement == null) {
                    log.warn("BidCancelledCommissionRefundListener: annonce introuvable pour bid {}"
                            + " — wallet refund impossible", bid.getId());
                    return;
                }
                cashCommissionService.refundCommissionToWallet(
                        bid, announcement.getTravelerId(), "wallet-refund-cancel-" + bid.getId());
                log.info("BidCancelledCommissionRefundListener: commission wallet recréditée pour bid {} (bid-cancel)",
                        bid.getId());
            }
            case CARD -> {
                cashCommissionService.refundCommission(bid);
                log.info("BidCancelledCommissionRefundListener: commission carte remboursée pour bid {} (bid-cancel)",
                        bid.getId());
            }
        }
    }
}

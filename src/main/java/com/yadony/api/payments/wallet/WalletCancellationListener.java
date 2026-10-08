package com.yadony.api.payments.wallet;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionRetention;
import com.yadony.api.payments.cash.CommissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Remboursement commission wallet sur annulation de voyage (trip-cancel).
 *
 * Délègue à CashCommissionService.refundCommissionToWallet afin de :
 * - bénéficier de la garde commissionStatus==CHARGED (prévient le double remboursement
 *   si un SENDER_NO_SHOW a déjà posé commissionStatus=REFUNDED sur le même bid),
 * - et de la transition commissionStatus→REFUNDED (clôture de la commission).
 *
 * Traite uniquement les bids CASH dont commissionChargedVia=WALLET.
 * Les bids CASH commissionChargedVia=CARD sont traités par CardCommissionTripCancelRefundListener.
 *
 * FLUTTER-E4 : annulation du fait du voyageur ({@link TripCancelledEvent#isTravelerInitiated()})
 * → la commission reste prélevée, l'audit trace la retenue.
 */
@Component
public class WalletCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(WalletCancellationListener.class);

    private final CashCommissionService cashCommissionService;
    private final BidRepository bidRepository;
    private final AuditService auditService;

    public WalletCancellationListener(CashCommissionService cashCommissionService,
                                      BidRepository bidRepository,
                                      AuditService auditService) {
        this.cashCommissionService = cashCommissionService;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripCancelled(TripCancelledEvent event) {
        List<UUID> bidIds = event.getAffectedBidIds();
        if (bidIds == null || bidIds.isEmpty()) return;

        UUID travelerId = event.getTravelerId();
        log.info("WalletCancellationListener: TripCancelledEvent for announcement={}, travelerId={}, {} bid(s)",
                event.getAnnouncementId(), travelerId, bidIds.size());

        for (UUID bidId : bidIds) {
            processWalletRefundForBid(bidId, travelerId, event);
        }
    }

    private void processWalletRefundForBid(UUID bidId, UUID travelerId, TripCancelledEvent event) {
        String paymentMethod = event.getBidPaymentMethods().getOrDefault(bidId, "STRIPE");
        if (!"CASH".equals(paymentMethod)) return;

        String via = event.getBidCommissionChargedVia().get(bidId);
        if (via != null && !CommissionChargedVia.WALLET.name().equals(via)) {
            log.debug("WalletCancellationListener: bid {} commissionChargedVia={} — not WALLET, skipping", bidId, via);
            return;
        }

        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            log.warn("WalletCancellationListener: bid {} introuvable — skip", bidId);
            return;
        }

        if (event.isTravelerInitiated()) {
            if (bid.getCommissionStatus() == CommissionStatus.CHARGED) {
                auditService.log("payment", bidId, CommissionRetention.AUDIT_ACTION, travelerId,
                        Map.of("reason", String.valueOf(event.getReason()),
                               "commissionChargedVia", CommissionChargedVia.WALLET.name()));
                log.info("WalletCancellationListener: commission conservée pour bid {} (annulation voyageur)", bidId);
            }
            return;
        }

        // Délégation à refundCommissionToWallet :
        // - garde commissionStatus==CHARGED (prévient double refund après no-show)
        // - transition commissionStatus→REFUNDED
        // - idempotence via clé "wallet-refund-cancel-{bidId}"
        cashCommissionService.refundCommissionToWallet(bid, travelerId, "wallet-refund-cancel-" + bidId);
    }
}

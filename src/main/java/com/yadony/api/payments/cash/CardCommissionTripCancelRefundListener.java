package com.yadony.api.payments.cash;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
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
 * Rembourse via Stripe Refund les commissions prélevées sur carte (via=CARD) lors d'une
 * annulation de trajet (TripCancelledEvent).
 *
 * Complète WalletCancellationListener (qui ne gère que via=WALLET), pour couvrir la matrice :
 *   | trip-cancel + via=CARD  → refund Stripe (ce listener)
 *   | trip-cancel + via=WALLET → crédit wallet (WalletCancellationListener)
 *
 * FLUTTER-E4 : annulation du fait du voyageur ({@link TripCancelledEvent#isTravelerInitiated()})
 * → aucun remboursement, la commission reste prélevée et l'audit trace la retenue.
 */
@Component
public class CardCommissionTripCancelRefundListener {

    private static final Logger log = LoggerFactory.getLogger(CardCommissionTripCancelRefundListener.class);

    private final CashCommissionService cashCommissionService;
    private final BidRepository bidRepository;
    private final AuditService auditService;

    public CardCommissionTripCancelRefundListener(CashCommissionService cashCommissionService,
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

        for (UUID bidId : bidIds) {
            processCardRefundForBid(bidId, event);
        }
    }

    private void processCardRefundForBid(UUID bidId, TripCancelledEvent event) {
        String paymentMethod = event.getBidPaymentMethods().getOrDefault(bidId, "STRIPE");
        if (!"CASH".equals(paymentMethod)) return;

        String via = event.getBidCommissionChargedVia().get(bidId);
        if (!"CARD".equals(via)) return;

        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            log.warn("CardCommissionTripCancelRefundListener: bid {} introuvable", bidId);
            return;
        }
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) return;

        if (event.isTravelerInitiated()) {
            auditService.log("payment", bidId, CommissionRetention.AUDIT_ACTION, event.getTravelerId(),
                    Map.of("reason", String.valueOf(event.getReason()),
                           "commissionChargedVia", CommissionChargedVia.CARD.name()));
            log.info("CardCommissionTripCancelRefundListener: commission conservée pour bid {} (annulation voyageur)", bidId);
            return;
        }

        cashCommissionService.refundCommission(bid);
        log.info("CardCommissionTripCancelRefundListener: refund Stripe effectué pour bid {} (trip-cancel)", bidId);
    }
}

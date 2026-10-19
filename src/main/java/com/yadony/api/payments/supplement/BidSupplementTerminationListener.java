package com.yadony.api.payments.supplement;

import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.HandoverExpiredEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.AdminPaymentRefundedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Fins de colis sans livraison (plan §5.1, catalogue de {@code PromoReleaseListener}) : la demande
 * de supplément encore ouverte est annulée. {@link BidSupplementService#onBidEnded} relit le statut
 * du colis et n'agit que s'il est réellement terminé sans livraison : un événement qui ne termine
 * pas le colis ({@code CancellationConfirmedEvent} hors no-show, remboursement admin d'un colis en
 * cours) ne ferme rien. Idempotent : plusieurs événements pour la même fin n'ont qu'un effet.
 *
 * <p>Le filet planifié ({@link BidSupplementReconciliationJob}) rattrape un événement perdu.
 */
@Component
public class BidSupplementTerminationListener {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementTerminationListener.class);

    private final BidSupplementService service;

    public BidSupplementTerminationListener(BidSupplementService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidRejected(BidRejectedEvent e) {
        end(e.getBidId(), "BID_REJECTED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripCancelled(TripCancelledEvent e) {
        if (e.getAffectedBidIds() != null) {
            e.getAffectedBidIds().forEach(bidId -> end(bidId, "TRIP_CANCELLED"));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onParcelRefused(ParcelRefusedEvent e) {
        end(e.getBidId(), "PARCEL_REFUSED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTravelerNoShow(VoyageurNoShowEvent e) {
        end(e.getBidId(), "TRAVELER_NO_SHOW");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onHandoverExpired(HandoverExpiredEvent e) {
        end(e.bidId(), "HANDOVER_EXPIRED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCancellationConfirmed(CancellationConfirmedEvent e) {
        end(e.bidId(), "CANCELLATION_CONFIRMED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidExpiredOnDeparture(BidExpiredOnDepartureEvent e) {
        end(e.getBidId(), "BID_EXPIRED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAdminPaymentRefunded(AdminPaymentRefundedEvent e) {
        end(e.bidId(), "ADMIN_REFUND");
    }

    /** Redondant avec {@link BidRejectedEvent} (annulation admin) : écouté par prudence, sans double effet. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAdminBidCancelled(AdminBidCancelledEvent e) {
        end(e.bidId(), "ADMIN_CANCELLED");
    }

    private void end(UUID bidId, String reason) {
        try {
            service.onBidEnded(bidId, reason);
        } catch (RuntimeException ex) {
            // Le filet planifié reprendra : un colis en échec ne bloque pas les autres.
            log.error("Annulation du supplément du colis {} ({}) en échec", bidId, reason, ex);
        }
    }
}

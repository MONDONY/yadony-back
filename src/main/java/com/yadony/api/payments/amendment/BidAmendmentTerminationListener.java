package com.yadony.api.payments.amendment;

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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Fins de colis sans livraison (même catalogue que {@code BidSupplementTerminationListener}) : les modifications du
 * colis sont closes ou remboursées, leurs kilos rendus au trajet (spec §5.4, point de vigilance n° 1). Toutes les
 * libérations de capacité existantes ne lisent que {@code bid.getWeightKg()} : ce point unique rend les kilos des
 * modifications, quel que soit le chemin de fin.
 *
 * <p>{@link BidAmendmentService#onBidEnded} relit le statut du colis et n'agit que s'il est réellement terminé sans
 * livraison ; il est idempotent (plusieurs événements pour la même fin n'ont qu'un effet). Pas de transaction propre
 * ici : le service ouvre la sienne (REQUIRES_NEW) colis par colis. Le filet planifié
 * ({@link BidAmendmentReconciliationJob}) rattrape un événement perdu.
 */
@Component
public class BidAmendmentTerminationListener {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentTerminationListener.class);

    private final BidAmendmentService service;

    public BidAmendmentTerminationListener(BidAmendmentService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidRejected(BidRejectedEvent e) {
        end(e.getBidId(), "BID_REJECTED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTripCancelled(TripCancelledEvent e) {
        if (e.getAffectedBidIds() != null) {
            e.getAffectedBidIds().forEach(bidId -> end(bidId, "TRIP_CANCELLED"));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onParcelRefused(ParcelRefusedEvent e) {
        end(e.getBidId(), "PARCEL_REFUSED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTravelerNoShow(VoyageurNoShowEvent e) {
        end(e.getBidId(), "TRAVELER_NO_SHOW");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onHandoverExpired(HandoverExpiredEvent e) {
        end(e.bidId(), "HANDOVER_EXPIRED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCancellationConfirmed(CancellationConfirmedEvent e) {
        end(e.bidId(), "CANCELLATION_CONFIRMED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidExpiredOnDeparture(BidExpiredOnDepartureEvent e) {
        end(e.getBidId(), "BID_EXPIRED");
    }

    /** Redondant avec {@link BidRejectedEvent} (annulation admin) : écouté par prudence, sans double effet. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onAdminBidCancelled(AdminBidCancelledEvent e) {
        end(e.bidId(), "ADMIN_CANCELLED");
    }

    /** Remboursement admin du principal : aussi sur un colis non terminé (hors litige ouvert). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onAdminPaymentRefunded(AdminPaymentRefundedEvent e) {
        try {
            service.onPrincipalRefunded(e.bidId());
        } catch (RuntimeException ex) {
            log.error("Modifications du colis {} (remboursement admin du principal) en échec", e.bidId(), ex);
        }
    }

    private void end(UUID bidId, String reason) {
        try {
            service.onBidEnded(bidId, reason);
        } catch (RuntimeException ex) {
            // Le filet planifié reprendra : un colis en échec ne bloque pas les autres.
            log.error("Fin des modifications du colis {} ({}) en échec", bidId, reason, ex);
        }
    }
}

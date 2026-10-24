package com.yadony.api.payments.amendment;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.HandoverExpiredEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.AdminPaymentRefundedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Une fin de colis par événement, chacune vers {@code onBidEnded} avec sa clé ; un échec n'arrête pas les autres. */
class BidAmendmentTerminationListenerTest {

    private final BidAmendmentService service = mock(BidAmendmentService.class);
    private final BidAmendmentTerminationListener listener = new BidAmendmentTerminationListener(service);
    private final UUID bidId = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void bidRejete() {
        listener.onBidRejected(new BidRejectedEvent(bidId, UUID.randomUUID(), "x", null, false));
        verify(service).onBidEnded(bidId, "BID_REJECTED");
    }

    @Test
    void trajetAnnule_chaqueColisTouche_memeSiLUnEchoue() {
        when(service.onBidEnded(bidId, "TRIP_CANCELLED")).thenThrow(new IllegalStateException("verrou"));
        listener.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(),
                "annulé", List.of(bidId, other)));
        verify(service).onBidEnded(bidId, "TRIP_CANCELLED");
        verify(service).onBidEnded(other, "TRIP_CANCELLED");
    }

    @Test
    void trajetAnnuleSansColis_sansEffet() {
        listener.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(), "annulé", null));
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void colisRefuse() {
        listener.onParcelRefused(new ParcelRefusedEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), "non conforme"));
        verify(service).onBidEnded(bidId, "PARCEL_REFUSED");
    }

    @Test
    void voyageurAbsent() {
        listener.onTravelerNoShow(new VoyageurNoShowEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), 1));
        verify(service).onBidEnded(bidId, "TRAVELER_NO_SHOW");
    }

    @Test
    void remiseExpiree() {
        listener.onHandoverExpired(new HandoverExpiredEvent(bidId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), true));
        verify(service).onBidEnded(bidId, "HANDOVER_EXPIRED");
    }

    @Test
    void annulationConfirmee() {
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(bidId, UUID.randomUUID(),
                CancellationReason.SENDER_NO_SHOW));
        verify(service).onBidEnded(bidId, "CANCELLATION_CONFIRMED");
    }

    @Test
    void colisExpireAuDepart() {
        listener.onBidExpiredOnDeparture(new BidExpiredOnDepartureEvent(bidId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID()));
        verify(service).onBidEnded(bidId, "BID_EXPIRED");
    }

    @Test
    void remboursementAdmin() {
        listener.onAdminPaymentRefunded(new AdminPaymentRefundedEvent(UUID.randomUUID(), bidId, null, UUID.randomUUID()));
        verify(service).onPrincipalRefunded(bidId);
    }

    @Test
    void remboursementAdmin_echecAvale() {
        when(service.onPrincipalRefunded(bidId)).thenThrow(new IllegalStateException("verrou"));
        listener.onAdminPaymentRefunded(new AdminPaymentRefundedEvent(UUID.randomUUID(), bidId, null, UUID.randomUUID()));
        verify(service).onPrincipalRefunded(bidId);
    }

    @Test
    void annulationAdmin() {
        listener.onAdminBidCancelled(new AdminBidCancelledEvent(bidId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, BigDecimal.ZERO, "EUR", false));
        verify(service).onBidEnded(bidId, "ADMIN_CANCELLED");
    }
}

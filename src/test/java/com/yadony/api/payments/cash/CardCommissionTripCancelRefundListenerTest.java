package com.yadony.api.payments.cash;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardCommissionTripCancelRefundListenerTest {

    @Mock private CashCommissionService cashCommissionService;
    @Mock private BidRepository bidRepository;

    private CardCommissionTripCancelRefundListener listener;

    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CardCommissionTripCancelRefundListener(cashCommissionService, bidRepository);
    }

    private TripCancelledEvent event(String paymentMethod, String chargedVia) {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "TRIP_CANCELLED",
                List.of(bidId),
                Map.of(bidId, paymentMethod),
                chargedVia == null ? Map.of() : Map.of(bidId, chargedVia));
    }

    private BidEntity chargedCashBid() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(CommissionChargedVia.CARD);
        return bid;
    }

    /** Annulation à l'initiative du voyageur (après remise) : sa commission lui est rendue. */
    @Test
    void refundsWhenTravelerCancelsAfterHandover() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(chargedCashBid()));

        listener.onTripCancelled(new TripCancelledEvent(
                announcementId, travelerId, List.of(), "TRAVELER_CANCEL_AFTER_HANDOVER",
                List.of(bidId), Map.of(bidId, "CASH"), Map.of(bidId, "CARD")));

        verify(cashCommissionService).refundCommission(any());
    }

    @Test
    void refundsViaStripeWhenCardCharged() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(chargedCashBid()));

        listener.onTripCancelled(event("CASH", "CARD"));

        verify(cashCommissionService).refundCommission(any());
    }

    @Test
    void skipsWhenChargedViaWallet() {
        listener.onTripCancelled(event("CASH", "WALLET"));

        verify(cashCommissionService, never()).refundCommission(any());
        verify(bidRepository, never()).findById(any());
    }

    @Test
    void skipsNonCashBid() {
        listener.onTripCancelled(event("STRIPE", "CARD"));

        verify(cashCommissionService, never()).refundCommission(any());
    }

    @Test
    void skipsWhenCommissionNotCharged() {
        BidEntity bid = chargedCashBid();
        bid.setCommissionStatus(CommissionStatus.FAILED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onTripCancelled(event("CASH", "CARD"));

        verify(cashCommissionService, never()).refundCommission(any());
    }

    @Test
    void skipsWhenNoBids() {
        listener.onTripCancelled(new TripCancelledEvent(
                announcementId, travelerId, List.of(), "TRIP_CANCELLED", List.of()));

        verify(cashCommissionService, never()).refundCommission(any());
    }
}

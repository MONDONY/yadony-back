package com.yadony.api.payments.cash;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.AuditService;
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
    @Mock private AuditService auditService;

    private CardCommissionTripCancelRefundListener listener;

    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CardCommissionTripCancelRefundListener(cashCommissionService, bidRepository, auditService);
    }

    private TripCancelledEvent travelerEvent() {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "Imprévu personnel",
                List.of(bidId), Map.of(bidId, "CASH"), Map.of(bidId, "CARD"), Map.of(), true);
    }

    @Test
    void retainsCommissionWhenTravelerCancels() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(chargedCashBid()));

        listener.onTripCancelled(travelerEvent());

        verify(cashCommissionService, never()).refundCommission(any());
        verify(auditService).log("payment", bidId, "COMMISSION_RETAINED_TRAVELER_CANCEL", travelerId,
                Map.of("reason", "Imprévu personnel", "commissionChargedVia", "CARD"));
    }

    @Test
    void travelerCancelWithUnchargedCommissionDoesNothing() {
        BidEntity bid = chargedCashBid();
        bid.setCommissionStatus(CommissionStatus.REFUNDED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onTripCancelled(travelerEvent());

        verifyNoInteractions(cashCommissionService, auditService);
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

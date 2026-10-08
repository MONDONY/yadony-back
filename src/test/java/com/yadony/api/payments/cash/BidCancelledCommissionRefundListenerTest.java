package com.yadony.api.payments.cash;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidCancelledCommissionRefundListenerTest {

    @Mock private CashCommissionService cashCommissionService;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;

    private BidCancelledCommissionRefundListener listener;

    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new BidCancelledCommissionRefundListener(
                cashCommissionService, bidRepository, announcementRepository, auditService);
    }

    /** Annulation par l'expéditeur : la commission du voyageur lui est rendue. */
    private BidRejectedEvent event() {
        return new BidRejectedEvent(bidId, senderId, "CANCELLED_BY_SENDER");
    }

    private BidRejectedEvent travelerCancelEvent() {
        return new BidRejectedEvent(bidId, senderId, "CANCELLED_BY_TRAVELER");
    }

    // --- FLUTTER-E4 : le voyageur qui annule ne récupère pas sa commission ---

    @Test
    void retainsWalletCommissionWhenTravelerCancels() {
        BidEntity bid = cashBid(CommissionChargedVia.WALLET);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement()));

        listener.onBidRejected(travelerCancelEvent());

        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
        verify(cashCommissionService, never()).refundCommission(any());
        verify(auditService).log("payment", bidId, "COMMISSION_RETAINED_TRAVELER_CANCEL", travelerId,
                Map.of("reason", "CANCELLED_BY_TRAVELER", "commissionChargedVia", "WALLET"));
    }

    @Test
    void retainsCardCommissionWhenTravelerCancels() {
        BidEntity bid = cashBid(CommissionChargedVia.CARD);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onBidRejected(travelerCancelEvent());

        verify(cashCommissionService, never()).refundCommission(any());
        verify(auditService).log(eq("payment"), eq(bidId), eq("COMMISSION_RETAINED_TRAVELER_CANCEL"),
                eq(null), any());
    }

    @Test
    void travelerCancelOnUnchargedCommissionWritesNoAudit() {
        BidEntity bid = cashBid(CommissionChargedVia.WALLET);
        bid.setCommissionStatus(CommissionStatus.REFUNDED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidRejected(travelerCancelEvent());

        verifyNoInteractions(auditService, cashCommissionService);
    }

    @Test
    void refundsOnOtherReasonsLikeSenderAccountDeletion() {
        BidEntity bid = cashBid(CommissionChargedVia.CARD);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidRejected(new BidRejectedEvent(bidId, senderId, "SENDER_ACCOUNT_DELETED"));

        verify(cashCommissionService).refundCommission(bid);
        verifyNoInteractions(auditService);
    }

    @Test
    void routesToWalletRefundWhenChargedViaWallet() {
        BidEntity bid = cashBid(CommissionChargedVia.WALLET);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement()));

        listener.onBidRejected(event());

        verify(cashCommissionService).refundCommissionToWallet(eq(bid), eq(travelerId),
                eq("wallet-refund-cancel-" + bidId));
        verify(cashCommissionService, never()).refundCommission(any());
    }

    @Test
    void routesToStripeRefundWhenChargedViaCard() {
        BidEntity bid = cashBid(CommissionChargedVia.CARD);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidRejected(event());

        verify(cashCommissionService).refundCommission(bid);
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void ignoresNonCashBid() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidRejected(event());

        verify(cashCommissionService, never()).refundCommission(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void ignoresBidWithUnchargedCommission() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(CommissionStatus.PENDING);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidRejected(event());

        verify(cashCommissionService, never()).refundCommission(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void ignoresBidWithNullChargedVia() {
        BidEntity bid = cashBid(null);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidRejected(event());

        verify(cashCommissionService, never()).refundCommission(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void ignoresMissingBid() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onBidRejected(event());

        verifyNoInteractions(cashCommissionService);
    }

    // --- helpers ---

    private BidEntity cashBid(CommissionChargedVia via) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(via);
        bid.setAnnouncementId(announcementId);
        return bid;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", announcementId);
        ann.setTravelerId(travelerId);
        return ann;
    }
}

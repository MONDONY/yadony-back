package com.yadony.api.payments.wallet;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionChargedVia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Le listener ne décide plus du crédit wallet lui-même : il filtre les bids
 * (CASH + commissionChargedVia WALLET/null) et délègue à
 * {@link CashCommissionService#refundCommissionToWallet} qui porte la garde
 * commissionStatus==CHARGED, la transition →REFUNDED et l'idempotence.
 * Ces tests vérifient donc le routage + la délégation, pas la mécanique du refund.
 */
@ExtendWith(MockitoExtension.class)
class WalletCancellationListenerTest {

    @Mock
    private CashCommissionService cashCommissionService;

    @Mock
    private BidRepository bidRepository;

    private WalletCancellationListener listener;

    @BeforeEach
    void setUp() {
        listener = new WalletCancellationListener(cashCommissionService, bidRepository);
    }

    // --- Helper builders ---

    private BidEntity bidWithId(UUID bidId) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        return bid;
    }

    /** CASH bid, commissionChargedVia non précisé (map vide → null → traité comme WALLET). */
    private TripCancelledEvent cashEvent(UUID announcementId, UUID travelerId, UUID bidId) {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "reason",
                List.of(bidId), Map.of(bidId, "CASH"));
    }

    /** CASH bid avec commissionChargedVia explicite. */
    private TripCancelledEvent cashEventVia(UUID announcementId, UUID travelerId, UUID bidId, CommissionChargedVia via) {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "reason",
                List.of(bidId), Map.of(bidId, "CASH"),
                Map.of(bidId, via.name()));
    }

    private TripCancelledEvent stripeEvent(UUID announcementId, UUID travelerId, UUID bidId) {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "reason",
                List.of(bidId), Map.of(bidId, "STRIPE"));
    }

    // --- Tests ---

    /** Annulation à l'initiative du voyageur (après remise) : sa commission lui est rendue. */
    @Test
    void onTripCancelled_travelerCancelAfterHandover_refundsCommissionToWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = bidWithId(bidId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onTripCancelled(new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "TRAVELER_CANCEL_AFTER_HANDOVER",
                List.of(bidId), Map.of(bidId, "CASH"), Map.of(bidId, "WALLET")));

        verify(cashCommissionService).refundCommissionToWallet(bid, travelerId, "wallet-refund-cancel-" + bidId);
    }

    @Test
    void onTripCancelled_cashBidViaWallet_delegatesWithNoShowDistinctKey() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        BidEntity bid = bidWithId(bidId);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        TripCancelledEvent event = cashEventVia(announcementId, travelerId, bidId, CommissionChargedVia.WALLET);
        listener.onTripCancelled(event);

        verify(cashCommissionService).refundCommissionToWallet(bid, travelerId, "wallet-refund-cancel-" + bidId);
    }

    @Test
    void onTripCancelled_cashBidViaNull_isTreatedAsWalletAndDelegates() {
        // commissionChargedVia absent de la map → null → le filtre laisse passer (rétro-compat).
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = bidWithId(bidId);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onTripCancelled(cashEvent(UUID.randomUUID(), travelerId, bidId));

        verify(cashCommissionService).refundCommissionToWallet(bid, travelerId, "wallet-refund-cancel-" + bidId);
    }

    @Test
    void onTripCancelled_cashBidViaCard_isSkipped() {
        // via=CARD → c'est CardCommissionTripCancelRefundListener qui rembourse, pas celui-ci.
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        listener.onTripCancelled(cashEventVia(UUID.randomUUID(), travelerId, bidId, CommissionChargedVia.CARD));

        verify(bidRepository, never()).findById(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void onTripCancelled_stripeBid_isSkipped() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        listener.onTripCancelled(stripeEvent(UUID.randomUUID(), travelerId, bidId));

        verify(bidRepository, never()).findById(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void onTripCancelled_bidNotInPaymentMethodMap_defaultsToStripe_isSkipped() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                List.of(bidId), Map.of() /* bidPaymentMethods vide */);

        listener.onTripCancelled(event);

        verify(bidRepository, never()).findById(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void onTripCancelled_cashBidNotFoundInRepo_doesNotDelegate() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onTripCancelled(cashEvent(UUID.randomUUID(), travelerId, bidId));

        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void onTripCancelled_emptyBidList_doesNothing() {
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                List.of(), Map.of());

        listener.onTripCancelled(event);

        verify(bidRepository, never()).findById(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void onTripCancelled_nullBidList_doesNothing() {
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                null, Map.of());

        listener.onTripCancelled(event);

        verify(bidRepository, never()).findById(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void onTripCancelled_multipleBids_routesEachByPaymentMethodAndVia() {
        UUID walletBid = UUID.randomUUID();   // CASH + WALLET → délégué
        UUID cardBid = UUID.randomUUID();     // CASH + CARD   → skip
        UUID stripeBid = UUID.randomUUID();   // STRIPE        → skip
        UUID travelerId = UUID.randomUUID();
        BidEntity walletBidEntity = bidWithId(walletBid);

        when(bidRepository.findById(walletBid)).thenReturn(Optional.of(walletBidEntity));

        Map<UUID, String> methods = new HashMap<>();
        methods.put(walletBid, "CASH");
        methods.put(cardBid, "CASH");
        methods.put(stripeBid, "STRIPE");
        Map<UUID, String> vias = new HashMap<>();
        vias.put(walletBid, CommissionChargedVia.WALLET.name());
        vias.put(cardBid, CommissionChargedVia.CARD.name());

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                List.of(walletBid, cardBid, stripeBid), methods, vias);

        listener.onTripCancelled(event);

        verify(cashCommissionService).refundCommissionToWallet(walletBidEntity, travelerId, "wallet-refund-cancel-" + walletBid);
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), eq(travelerId), eq("wallet-refund-cancel-" + cardBid));
        verify(bidRepository, never()).findById(cardBid);
        verify(bidRepository, never()).findById(stripeBid);
    }
}

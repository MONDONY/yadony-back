package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.cancellation.events.TripCancelRefusedDepositPaidEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.amendment.BidAmendmentRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * « La plateforme a-t-elle réellement payé le transporteur au dépôt ? » : principal en ligne libéré (relu sous verrou),
 * ou modification versée ; jamais la seule règle figée, jamais un principal en espèces.
 */
@ExtendWith(MockitoExtension.class)
class DepositPayoutAdapterTest {

    private static final UUID BID = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();

    @Mock BidRepository bids;
    @Mock PaymentRepository payments;
    @Mock BidAmendmentRepository amendments;
    @InjectMocks DepositPayoutAdapter adapter;

    @Test
    void deliveryBid_false_noPaymentRead() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.STRIPE, PayoutTrigger.DELIVERY)));

        assertThat(adapter.paidAtDeposit(BID)).isFalse();
        verifyNoInteractions(payments, amendments);
    }

    @Test
    void unknownBid_false() {
        when(bids.findById(BID)).thenReturn(Optional.empty());

        assertThat(adapter.paidAtDeposit(BID)).isFalse();
    }

    @Test
    void cardPrincipalReleased_true_readUnderLock() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.STRIPE, PayoutTrigger.DEPOSIT)));
        when(payments.findForBid(BID)).thenReturn(Optional.of(payment(PaymentStatus.ESCROW)));
        when(payments.findStatusById(PAYMENT)).thenReturn(Optional.of(PaymentStatus.RELEASED));

        assertThat(adapter.paidAtDeposit(BID)).isTrue();
        var order = inOrder(payments);
        order.verify(payments).lockIfEscrow(PAYMENT);
        order.verify(payments).findStatusById(PAYMENT);
        verifyNoInteractions(amendments);
    }

    @Test
    void mobileMoneyPrincipalStillInEscrow_falseUnlessAmendmentPaid() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.MOBILE_MONEY, PayoutTrigger.DEPOSIT)));
        when(payments.findForBid(BID)).thenReturn(Optional.of(payment(PaymentStatus.ESCROW)));
        when(payments.findStatusById(PAYMENT)).thenReturn(Optional.of(PaymentStatus.ESCROW));
        when(amendments.existsByBidIdAndRailNotAndStatusIn(BID, BidSupplementRail.CASH,
                DepositPayoutAdapter.AMENDMENT_PAID_OUT)).thenReturn(false);

        assertThat(adapter.paidAtDeposit(BID)).isFalse();
    }

    @Test
    void cashPrincipal_neverReadsPayment_amendmentPaidOutCounts() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.CASH, PayoutTrigger.DEPOSIT)));
        when(amendments.existsByBidIdAndRailNotAndStatusIn(BID, BidSupplementRail.CASH,
                DepositPayoutAdapter.AMENDMENT_PAID_OUT)).thenReturn(true, false);

        assertThat(adapter.paidAtDeposit(BID)).isTrue();
        assertThat(adapter.paidAtDeposit(BID)).isFalse();
        verifyNoInteractions(payments);
    }

    @Test
    void noPaymentFound_fallsBackToAmendments() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.STRIPE, PayoutTrigger.DEPOSIT)));
        when(payments.findForBid(BID)).thenReturn(Optional.empty());

        assertThat(adapter.paidAtDeposit(BID)).isFalse();
        verify(payments, never()).lockIfEscrow(any());
    }

    @Test
    void tripCancelAlert_oncePerTrip_typeFitsColumn_failureSwallowed() {
        AdminAlertEscalator escalator = mock(AdminAlertEscalator.class);
        DepositPaidTripCancelAlertListener listener = new DepositPaidTripCancelAlertListener(escalator);
        UUID trip = UUID.randomUUID();

        listener.onTripCancelRefused(new TripCancelRefusedDepositPaidEvent(trip, UUID.randomUUID(), List.of(BID)));

        String type = DepositPaidTripCancelAlertListener.ALERT_PREFIX + trip;
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        verify(escalator).raiseOnce(eq(type), anyString(), anyMap());

        doThrow(new IllegalStateException("base indisponible")).when(escalator)
                .raiseOnce(anyString(), anyString(), any(Map.class));
        listener.onTripCancelRefused(new TripCancelRefusedDepositPaidEvent(trip, null, List.of(BID)));
    }

    private static BidEntity bid(PaymentMethod method, PayoutTrigger trigger) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID);
        bid.setPaymentMethod(method);
        ReflectionTestUtils.setField(bid, "payoutTrigger", trigger);
        if (trigger == PayoutTrigger.DEPOSIT) {
            ReflectionTestUtils.setField(bid, "payoutTriggerFrozenAt", LocalDateTime.now());
        }
        return bid;
    }

    private static PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", PAYMENT);
        p.setStatus(status);
        return p;
    }
}

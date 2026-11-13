package com.yadony.api.payments.amendment;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quand partent les modifications acquises : avec le principal versé, ou, principal en espèces, au moment où il aurait
 * été versé (livraison pour un colis payé à la livraison, dépôt pour un colis payé au dépôt), jamais aux deux.
 */
@ExtendWith(MockitoExtension.class)
class BidAmendmentPayoutListenerTest {

    private static final UUID BID = UUID.randomUUID();

    @Mock BidAmendmentPayouts payouts;
    @Mock BidRepository bids;
    @InjectMocks BidAmendmentPayoutListener listener;

    @Test
    void cashDepositBid_amendmentPaidAtDeposit() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.CASH, PayoutTrigger.DEPOSIT)));

        listener.onParcelDeposited(new ParcelDepositedEvent(BID, UUID.randomUUID(), UUID.randomUUID()));

        verify(payouts).payoutForBid(BID);
    }

    @Test
    void cashDepositBid_deliveryPaysNothing() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.CASH, PayoutTrigger.DEPOSIT)));

        listener.onDeliveryConfirmed(delivered());

        verify(payouts, never()).payoutForBid(any());
    }

    @Test
    void cashDeliveryBid_deliveryPays_unchanged() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.CASH, PayoutTrigger.DELIVERY)));

        listener.onDeliveryConfirmed(delivered());

        verify(payouts).payoutForBid(BID);
    }

    @Test
    void cashDeliveryBid_depositIgnored() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.CASH, PayoutTrigger.DELIVERY)));

        listener.onParcelDeposited(new ParcelDepositedEvent(BID, UUID.randomUUID(), UUID.randomUUID()));

        verify(payouts, never()).payoutForBid(any());
    }

    @Test
    void cardDepositBid_depositAndDeliveryIgnored_principalReleaseDrivesPayout() {
        when(bids.findById(BID)).thenReturn(Optional.of(bid(PaymentMethod.STRIPE, PayoutTrigger.DEPOSIT)));

        listener.onParcelDeposited(new ParcelDepositedEvent(BID, UUID.randomUUID(), UUID.randomUUID()));
        listener.onDeliveryConfirmed(delivered());
        verify(payouts, never()).payoutForBid(any());

        PaymentReleasedEvent released = mock(PaymentReleasedEvent.class);
        when(released.getBidId()).thenReturn(BID);
        listener.onPaymentReleased(released);
        verify(payouts).payoutForBid(BID);
    }

    @Test
    void unknownBid_nothing() {
        when(bids.findById(BID)).thenReturn(Optional.empty());

        listener.onParcelDeposited(new ParcelDepositedEvent(BID, UUID.randomUUID(), UUID.randomUUID()));
        listener.onDeliveryConfirmed(delivered());

        verify(payouts, never()).payoutForBid(any());
    }

    private static DeliveryConfirmedEvent delivered() {
        DeliveryConfirmedEvent e = mock(DeliveryConfirmedEvent.class);
        when(e.getBidId()).thenReturn(BID);
        return e;
    }

    private static BidEntity bid(PaymentMethod method, PayoutTrigger trigger) {
        BidEntity bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, BID);
        bid.setPaymentMethod(method);
        setField(bid, "payoutTrigger", trigger);
        if (trigger == PayoutTrigger.DEPOSIT) {
            setField(bid, "payoutTriggerFrozenAt", LocalDateTime.now());
        }
        return bid;
    }

    private static void setField(BidEntity bid, String name, Object value) {
        try {
            Field f = BidEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(bid, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

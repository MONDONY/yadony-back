package com.yadony.api.matching.reception;

import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidMaterializedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceptionLinkListenerTest {

    @Mock ReceptionLinker linker;
    @InjectMocks ReceptionLinkListener listener;

    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @Test
    void bidAccepted_cardOrCash_links() {
        listener.onBidAccepted(new BidAcceptedEvent(bidId, senderId, travelerId, UUID.randomUUID()));
        verify(linker).linkIfPossible(bidId);
    }

    @Test
    void bidAccepted_mobileMoney_waitsForPaymentConfirmation() {
        listener.onBidAccepted(new BidAcceptedEvent(bidId, senderId, travelerId, UUID.randomUUID(), true));
        verify(linker, never()).linkIfPossible(any());
    }

    @Test
    void mobileMoneyPaymentConfirmed_links() {
        listener.onMobileMoneyPaymentConfirmed(new MobileMoneyPaymentConfirmedEvent(
                bidId, senderId, travelerId, BigDecimal.TEN, "XOF"));
        verify(linker).linkIfPossible(bidId);
    }

    @Test
    void bidMaterialized_links() {
        listener.onBidMaterialized(new BidMaterializedEvent(UUID.randomUUID(), bidId));
        verify(linker).linkIfPossible(bidId);
    }

    @Test
    void linkerFailure_isSwallowed() {
        when(linker.linkIfPossible(bidId)).thenThrow(new IllegalStateException("boom"));
        assertThatCode(() -> listener.onBidMaterialized(new BidMaterializedEvent(UUID.randomUUID(), bidId)))
                .doesNotThrowAnyException();
    }
}

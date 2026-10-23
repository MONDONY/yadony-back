package com.yadony.api.payments.amendment;

import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidAmendmentCashListener : suites du rail espèces après le commit")
class BidAmendmentCashListenerTest {

    @Mock BidAmendmentCashRefunds refunds;

    private BidAmendmentCashListener listener;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new BidAmendmentCashListener(refunds);
    }

    private BidAmendmentClosedEvent closed(BidSupplementRail rail) {
        return new BidAmendmentClosedEvent.Cancelled(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), rail,
                BidAmendmentStatus.COMMISSION_PENDING, BidAmendmentClosedReason.COMMISSION_NOT_CONFIRMED);
    }

    @Test
    void closedCashAmendment_settlesCommission() {
        listener.onClosed(closed(BidSupplementRail.CASH));
        verify(refunds).settle(id);
    }

    @Test
    void closedCardAmendment_ignored() {
        listener.onClosed(closed(BidSupplementRail.STRIPE));
        verifyNoInteractions(refunds);
    }

    @Test
    void lateCommission_settles() {
        listener.onRefundRequested(new BidAmendmentRefundRequestedEvent(id, UUID.randomUUID(),
                BidSupplementRail.CASH, "LATE_COMMISSION"));
        verify(refunds).settle(id);
    }

    @Test
    void refundRequestedForOtherRail_ignored() {
        listener.onRefundRequested(new BidAmendmentRefundRequestedEvent(id, UUID.randomUUID(),
                BidSupplementRail.PAWAPAY, "NOT_BROUGHT"));
        verifyNoInteractions(refunds);
    }

    @Test
    void failureIsLoggedNotPropagated() {
        doThrow(new IllegalStateException("boom")).when(refunds).settle(id);
        listener.onClosed(closed(BidSupplementRail.CASH));
        verify(refunds).settle(id);
    }
}

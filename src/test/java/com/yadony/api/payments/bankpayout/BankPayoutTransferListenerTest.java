package com.yadony.api.payments.bankpayout;

import com.yadony.api.payments.events.StripeTransferRecordedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BankPayoutTransferListenerTest {

    @Test
    void delegates_andNeverFailsTheCaller() {
        BankPayoutTrackingService service = mock(BankPayoutTrackingService.class);
        BankPayoutTransferListener listener = new BankPayoutTransferListener(service);
        StripeTransferRecordedEvent event = new StripeTransferRecordedEvent(UUID.randomUUID(), null, null, "tr", "acct");

        listener.onTransferRecorded(event);
        verify(service).recordTransfer(event);

        doThrow(new IllegalStateException("boom")).when(service).recordTransfer(event);
        assertThatCode(() -> listener.onTransferRecorded(event)).doesNotThrowAnyException();
    }
}

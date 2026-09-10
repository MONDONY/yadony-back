package com.yadony.api.requests.service;

import static org.mockito.Mockito.verify;

import com.yadony.api.payments.events.MobileMoneyNegotiationDepositConfirmedEvent;
import com.yadony.api.payments.events.MobileMoneyNegotiationDepositFailedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiationDepositListenerTest {

    @Mock NegotiationService service;
    @InjectMocks NegotiationDepositListener listener;

    @Test
    void confirmed_finalizes() {
        UUID threadId = UUID.randomUUID();
        listener.onDepositConfirmed(new MobileMoneyNegotiationDepositConfirmedEvent(threadId, UUID.randomUUID(), UUID.randomUUID()));
        verify(service).finalizeAfterMobileMoneyDeposit(threadId);
    }

    @Test
    void failed_goesThroughThePort() {
        UUID threadId = UUID.randomUUID();
        listener.onDepositFailed(new MobileMoneyNegotiationDepositFailedEvent(threadId, UUID.randomUUID(), "PAYER_LIMIT_REACHED"));
        verify(service).failMobileMoneyDeposit(threadId);
    }
}

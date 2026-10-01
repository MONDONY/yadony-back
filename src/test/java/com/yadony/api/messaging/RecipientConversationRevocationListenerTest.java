package com.yadony.api.messaging;

import com.yadony.api.matching.events.BidRecipientChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class RecipientConversationRevocationListenerTest {

    private final RecipientConversationService service = mock(RecipientConversationService.class);
    private final RecipientConversationRevocationListener listener = new RecipientConversationRevocationListener(service);

    @Test
    void recipientChange_closesTheStaleRecipientConversation() {
        UUID bidId = UUID.randomUUID();
        when(service.revokeStale(bidId)).thenReturn(1);

        listener.onBidRecipientChanged(new BidRecipientChangedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(service).revokeStale(bidId);
    }

    @Test
    void failure_isSwallowed() {
        UUID bidId = UUID.randomUUID();
        when(service.revokeStale(bidId)).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> listener.onBidRecipientChanged(new BidRecipientChangedEvent(bidId, null, null)))
                .doesNotThrowAnyException();
    }
}

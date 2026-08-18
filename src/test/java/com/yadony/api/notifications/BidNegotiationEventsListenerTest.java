package com.yadony.api.notifications;

import com.yadony.api.matching.BidNegotiationMessageKind;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidNegotiationEventsListener — notifications du fil de négociation")
class BidNegotiationEventsListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private BidNegotiationEventsListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    @Test
    @DisplayName("seule la contrepartie est notifiée, jamais l'auteur")
    void notifiesOnlyTheCounterparty() {
        listener.onMessagePosted(new BidNegotiationMessagePostedEvent(
                BID_ID, ANNOUNCEMENT_ID, AUTHOR_ID, RECIPIENT_ID,
                BidNegotiationMessageKind.COUNTER, new BigDecimal("40.00"), 2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(RECIPIENT_ID), anyString(), anyString(), data.capture());
        assertThat(data.getValue()).containsEntry("type", "bid_negotiation_message");
        assertThat(data.getValue()).containsEntry("bidId", BID_ID.toString());
        assertThat(data.getValue()).containsEntry("kind", "COUNTER");
    }

    @Test
    @DisplayName("un message sans montant ne casse pas la mise en forme")
    void handlesMessageWithoutAmount() {
        listener.onMessagePosted(new BidNegotiationMessagePostedEvent(
                BID_ID, ANNOUNCEMENT_ID, AUTHOR_ID, RECIPIENT_ID,
                BidNegotiationMessageKind.REJECT, null, 2));

        verify(dispatcher).notifyUser(eq(RECIPIENT_ID), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("l'expiration prévient les deux parties")
    void expiryNotifiesBothParties() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        listener.onExpired(new BidNegotiationExpiredEvent(
                BID_ID, ANNOUNCEMENT_ID, senderId, travelerId, "INACTIVE"));

        verify(dispatcher).notifyUser(eq(senderId), anyString(), anyString(), anyMap());
        verify(dispatcher).notifyUser(eq(travelerId), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("chaque type de message porte son propre titre")
    void everyKindHasItsOwnTitle() {
        for (BidNegotiationMessageKind kind : BidNegotiationMessageKind.values()) {
            listener.onMessagePosted(new BidNegotiationMessagePostedEvent(
                    BID_ID, ANNOUNCEMENT_ID, AUTHOR_ID, RECIPIENT_ID,
                    kind, new BigDecimal("45.00"), 1));
        }
        ArgumentCaptor<String> titles = ArgumentCaptor.forClass(String.class);
        verify(dispatcher, org.mockito.Mockito.times(BidNegotiationMessageKind.values().length))
                .notifyUser(any(), titles.capture(), anyString(), anyMap());
        assertThat(titles.getAllValues()).doesNotHaveDuplicates();
    }
}

package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.BidNegotiationMessageKind;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidNegotiationEventsListener — notifications du fil de négociation")
class BidNegotiationEventsListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private BidNegotiationEventsListener listener;

    /** Dispatcher mocké : sans stub, {@code messagesFor} rend {@code null} → NPE. Français par défaut. */
    @BeforeEach
    void stubMessages() {
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

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
        verify(dispatcher).notifyUnlessBlocked(eq(RECIPIENT_ID), eq(AUTHOR_ID), anyString(), anyString(), data.capture());
        assertThat(data.getValue()).containsEntry("type", "bid_negotiation_message");
        assertThat(data.getValue()).containsEntry("bidId", BID_ID.toString());
        assertThat(data.getValue()).containsEntry("kind", "COUNTER");
    }

    @Test
    @DisplayName("le montant est dans la devise du trajet, tel que le destinataire le voit")
    void amountInTripCurrencyAsSeenByRecipient() {
        // Voyageur destinataire : il voit son net (9 000 F CFA), pas le brut de l'expéditeur,
        // et jamais « € » sur un trajet en francs CFA.
        listener.onMessagePosted(new BidNegotiationMessagePostedEvent(
                BID_ID, ANNOUNCEMENT_ID, AUTHOR_ID, RECIPIENT_ID,
                BidNegotiationMessageKind.PROPOSAL, new BigDecimal("10080"), 1,
                "XOF", new BigDecimal("9000")));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).notifyUnlessBlocked(eq(RECIPIENT_ID), eq(AUTHOR_ID), anyString(), body.capture(), anyMap());
        assertThat(body.getValue()).contains("9\u00A0000\u00A0F CFA").doesNotContain("€").doesNotContain("10080");
    }

    @Test
    @DisplayName("un message sans montant ne casse pas la mise en forme")
    void handlesMessageWithoutAmount() {
        listener.onMessagePosted(new BidNegotiationMessagePostedEvent(
                BID_ID, ANNOUNCEMENT_ID, AUTHOR_ID, RECIPIENT_ID,
                BidNegotiationMessageKind.REJECT, null, 2));

        verify(dispatcher).notifyUnlessBlocked(eq(RECIPIENT_ID), eq(AUTHOR_ID), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("l'expiration prévient les deux parties")
    void expiryNotifiesBothParties() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        listener.onExpired(new BidNegotiationExpiredEvent(
                BID_ID, ANNOUNCEMENT_ID, senderId, travelerId, "INACTIVE"));

        verify(dispatcher).notifyUnlessBlocked(eq(senderId), eq(travelerId), anyString(), anyString(), anyMap());
        verify(dispatcher).notifyUnlessBlocked(eq(travelerId), eq(senderId), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("date limite de dépôt passée : texte dédié aux deux parties (FLUTTER-GA)")
    void handoverDeadlineExpiryUsesDedicatedText() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        listener.onExpired(new BidNegotiationExpiredEvent(
                BID_ID, ANNOUNCEMENT_ID, senderId, travelerId, "HANDOVER_DEADLINE_PASSED"));

        verify(dispatcher).notifyUnlessBlocked(eq(senderId), eq(travelerId),
                eq("Date limite de dépôt passée"),
                eq("La date limite de dépôt est passée : demande annulée."), anyMap());
        verify(dispatcher).notifyUnlessBlocked(eq(travelerId), eq(senderId),
                eq("Date limite de dépôt passée"), anyString(), anyMap());
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
                .notifyUnlessBlocked(any(), any(), titles.capture(), anyString(), anyMap());
        assertThat(titles.getAllValues()).doesNotHaveDuplicates();
    }
}

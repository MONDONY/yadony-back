package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.BidCardAuthorizationExpiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("Notification « autorisation de paiement expirée »")
class CardAuthorizationExpiredNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private CardAuthorizationExpiredNotificationListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void stubMessages() {
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @Test
    @DisplayName("seul l'expéditeur est prévenu, vers sa demande")
    void notifiesSenderOnly() {
        listener.onCardAuthorizationExpired(new BidCardAuthorizationExpiredEvent(
                BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(SENDER_ID), eq("Paiement par carte expiré"),
                eq("Voyageur sans réponse : vous n'êtes pas débité. Refaites la demande."),
                data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "BID_EXPIRED")
                .containsEntry("reason", "CARD_AUTHORIZATION_EXPIRED")
                .containsEntry("bidId", BID_ID.toString())
                .containsEntry("announcementId", ANNOUNCEMENT_ID.toString());
        verify(dispatcher).messagesFor(SENDER_ID);
        verifyNoMoreInteractions(dispatcher);
    }

    @Test
    @DisplayName("trajet inconnu : la notification ouvre quand même la demande")
    void withoutAnnouncement_stillCarriesBid() {
        Map<String, String> data = CardAuthorizationExpiredNotificationListener.data(
                new BidCardAuthorizationExpiredEvent(BID_ID, null, SENDER_ID, null));

        assertThat(data).containsEntry("bidId", BID_ID.toString())
                .doesNotContainKey("announcementId");
    }
}

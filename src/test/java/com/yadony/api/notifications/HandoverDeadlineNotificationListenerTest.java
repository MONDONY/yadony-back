package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.BidHandoverDeadlinePassedEvent;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FLUTTER-GA : notification « date limite de dépôt passée »")
class HandoverDeadlineNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private HandoverDeadlineNotificationListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void stubMessages() {
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @Test
    @DisplayName("les deux parties sont prévenues, l'expéditeur du remboursement")
    void notifiesBothParties() {
        listener.onHandoverDeadlinePassed(new BidHandoverDeadlinePassedEvent(
                BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID, true, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(SENDER_ID), eq("Date limite de dépôt passée"),
                eq("La date limite de dépôt est passée : demande annulée et remboursée."), data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "BID_EXPIRED")
                .containsEntry("reason", "HANDOVER_DEADLINE_PASSED")
                .containsEntry("bidId", BID_ID.toString());
        verify(dispatcher).notifyUser(eq(TRAVELER_ID), eq("Date limite de dépôt passée"),
                eq("La date limite de dépôt est passée : demande annulée."), anyMap());
    }

    @Test
    @DisplayName("demande jamais présentée au voyageur : seul l'expéditeur est prévenu")
    void travelerSkippedWhenHeNeverSawTheRequest() {
        listener.onHandoverDeadlinePassed(new BidHandoverDeadlinePassedEvent(
                BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID, false, false));

        verify(dispatcher).notifyUser(eq(SENDER_ID), anyString(),
                eq("La date limite de dépôt est passée : demande annulée."), anyMap());
        verify(dispatcher, never()).notifyUser(eq(TRAVELER_ID), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("demande carte jamais payée, supprimée : la notification ouvre le trajet, pas la demande")
    void removedRequestOpensTheTrip() {
        listener.onHandoverDeadlinePassed(new BidHandoverDeadlinePassedEvent(
                BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID, false, false, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(SENDER_ID), anyString(),
                eq("La date limite de dépôt est passée : demande annulée."), data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "BID_EXPIRED")
                .containsEntry("reason", "HANDOVER_DEADLINE_PASSED")
                .containsEntry("announcementId", ANNOUNCEMENT_ID.toString())
                .doesNotContainKey("bidId");
        assertThat(NotificationDeeplink.of("BID_EXPIRED", data.getValue()))
                .contains("yadony://traveler/" + ANNOUNCEMENT_ID);
        verify(dispatcher, never()).notifyUser(eq(TRAVELER_ID), anyString(), anyString(), anyMap());
    }
}

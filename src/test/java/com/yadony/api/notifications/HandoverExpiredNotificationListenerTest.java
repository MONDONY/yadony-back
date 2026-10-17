package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.HandoverExpiredEvent;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FLUTTER-QR : notification « remise non effectuée » (espèces, sans fautif)")
class HandoverExpiredNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private HandoverExpiredNotificationListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void stubMessages() {
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @Test
    @DisplayName("les deux parties sont prévenues, texte neutre ; le voyageur apprend que sa commission est rendue")
    void notifiesBothPartiesNeutrally() {
        listener.onHandoverExpired(new HandoverExpiredEvent(BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(SENDER_ID), eq("Remise non effectuée"),
                eq("Aucune remise confirmée : envoi annulé, sans pénalité."), data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "BID_EXPIRED")
                .containsEntry("reason", "HANDOVER_NOT_CONFIRMED")
                .containsEntry("bidId", BID_ID.toString())
                .containsEntry("announcementId", ANNOUNCEMENT_ID.toString());
        verify(dispatcher).notifyUser(eq(TRAVELER_ID), eq("Remise non effectuée"),
                eq("Aucune remise confirmée : envoi annulé, commission rendue."), anyMap());
    }

    @Test
    @DisplayName("sans commission prélevée, le voyageur reçoit le texte sans remboursement")
    void travelerWithoutCommission_getsPlainText() {
        listener.onHandoverExpired(new HandoverExpiredEvent(BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID, false));

        verify(dispatcher).notifyUser(eq(TRAVELER_ID), eq("Remise non effectuée"),
                eq("Aucune remise confirmée : envoi annulé, sans pénalité."), anyMap());
    }

    @Test
    @DisplayName("chaque partie dans sa langue")
    void eachPartyInItsLanguage() {
        when(dispatcher.messagesFor(TRAVELER_ID)).thenReturn(TestMessages.en());

        listener.onHandoverExpired(new HandoverExpiredEvent(BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID, true));

        verify(dispatcher).notifyUser(eq(TRAVELER_ID), eq("Drop-off not completed"),
                eq("No drop-off was confirmed: shipment cancelled, fee refunded."), anyMap());
    }

    @Test
    @DisplayName("voyageur inconnu (trajet introuvable) : seul l'expéditeur est prévenu, sans announcementId")
    void unknownTraveler_onlySenderNotified() {
        listener.onHandoverExpired(new HandoverExpiredEvent(BID_ID, null, SENDER_ID, null, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(SENDER_ID), anyString(), anyString(), data.capture());
        assertThat(data.getValue()).doesNotContainKey("announcementId");
        verify(dispatcher, never()).notifyUser(eq(TRAVELER_ID), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("expéditeur inconnu : seul le voyageur est prévenu")
    void unknownSender_onlyTravelerNotified() {
        listener.onHandoverExpired(new HandoverExpiredEvent(BID_ID, ANNOUNCEMENT_ID, null, TRAVELER_ID, true));

        verify(dispatcher).notifyUser(eq(TRAVELER_ID), anyString(), anyString(), anyMap());
        verify(dispatcher, never()).notifyUser(eq(SENDER_ID), anyString(), anyString(), anyMap());
    }
}

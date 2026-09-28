package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.support.SupportMessageAuthorType;
import com.yadony.api.support.events.SupportMessageCreatedEvent;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportMessageEventListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private SupportMessageEventListener listener;

    private final UUID ticketId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void notifiesTheTicketOwnerWhenAnAdminReplies() {
        when(notificationDispatcher.messagesFor(ownerId)).thenReturn(TestMessages.fr());

        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.ADMIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), eq("Le support vous a répondu"), eq("Ouvrez votre demande pour lire la réponse."), data.capture());

        assertThat(data.getValue()).containsEntry("type", "SUPPORT_MESSAGE");
        assertThat(data.getValue()).containsEntry("ticketId", ticketId.toString());
    }

    @Test
    void notifiesTheTicketOwnerInEnglish_whenRecipientPrefersEnglish() {
        when(notificationDispatcher.messagesFor(ownerId)).thenReturn(TestMessages.en());

        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.ADMIN));

        verify(notificationDispatcher).notifyUser(eq(ownerId), eq("Support replied to you"),
                eq("Open your request to read the reply."), any());
    }

    /** Premier message d'une conversation ouverte par le support : nouveau message, pas « une réponse ». */
    @Test
    void announcesANewConversationWhenTheSupportStartsIt() {
        when(notificationDispatcher.messagesFor(ownerId)).thenReturn(TestMessages.fr());

        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.ADMIN, true, "Votre vérification"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), eq("Nouveau message de Yadony"),
                eq("L'équipe Yadony vous a écrit : Votre vérification"), data.capture());
        assertThat(data.getValue()).containsEntry("type", "SUPPORT_MESSAGE")
                .containsEntry("ticketId", ticketId.toString());
    }

    @Test
    void announcesANewConversationInEnglish_withALongSubjectCutAtAWord() {
        when(notificationDispatcher.messagesFor(ownerId)).thenReturn(TestMessages.en());

        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.ADMIN, true,
                "Your identity check has to be done again before your next parcel"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), eq("New message from Yadony"),
                body.capture(), any());
        assertThat(body.getValue()).startsWith("The Yadony team wrote to you: Your identity")
                .endsWith("…")
                .hasSizeLessThanOrEqualTo(NotificationCaps.BODY_MAX);
    }

    /** Le propre message de l'utilisateur ne doit pas lui revenir en push. */
    @Test
    void staysSilentWhenTheAuthorIsTheUser() {
        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.USER));

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }
}

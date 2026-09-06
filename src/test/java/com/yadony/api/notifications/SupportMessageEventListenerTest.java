package com.yadony.api.notifications;

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

@ExtendWith(MockitoExtension.class)
class SupportMessageEventListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private SupportMessageEventListener listener;

    private final UUID ticketId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void notifiesTheTicketOwnerWhenAnAdminReplies() {
        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.ADMIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), eq("Le support vous a repondu"), eq("Ouvrez votre demande pour lire la reponse."), data.capture());

        assertThat(data.getValue()).containsEntry("type", "SUPPORT_MESSAGE");
        assertThat(data.getValue()).containsEntry("ticketId", ticketId.toString());
    }

    /** Le propre message de l'utilisateur ne doit pas lui revenir en push. */
    @Test
    void staysSilentWhenTheAuthorIsTheUser() {
        listener.onSupportMessage(new SupportMessageCreatedEvent(
                ticketId, UUID.randomUUID(), ownerId, SupportMessageAuthorType.USER));

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }
}

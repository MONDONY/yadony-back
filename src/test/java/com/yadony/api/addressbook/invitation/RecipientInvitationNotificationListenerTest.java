package com.yadony.api.addressbook.invitation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientInvitationNotificationListenerTest {

    @Mock UserRepository userRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks RecipientInvitationNotificationListener listener;

    private final UUID invitationId = UUID.randomUUID();
    private final UUID inviterId = UUID.randomUUID();
    private final UUID inviteeId = UUID.randomUUID();

    @Test
    void pushesInvitationToInviteeWithInviterFirstName() {
        UserEntity inviter = new UserEntity();
        inviter.setFirstName("Awa");
        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(notificationDispatcher.messagesFor(inviteeId)).thenReturn(TestMessages.fr());

        listener.onInvitationCreated(new RecipientInvitationCreatedEvent(invitationId, inviterId, inviteeId));

        verify(notificationDispatcher).notifyUser(inviteeId, "Nouvelle demande",
                "Awa veut vous ajouter à ses destinataires Yadony.",
                Map.of("type", "RECIPIENT_INVITATION", "invitationId", invitationId.toString()));
    }

    @Test
    void pushesRemovalToInviteeWithInviterFirstName() {
        UserEntity inviter = new UserEntity();
        inviter.setFirstName("Awa");
        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(notificationDispatcher.messagesFor(inviteeId)).thenReturn(TestMessages.fr());

        listener.onInvitationRemoved(new RecipientInvitationRemovedEvent(invitationId, inviterId, inviteeId));

        verify(notificationDispatcher).notifyUser(inviteeId, "Destinataires Yadony",
                "Awa ne vous compte plus parmi ses destinataires Yadony.",
                Map.of("type", "RECIPIENT_INVITATION_REMOVED", "invitationId", invitationId.toString()));
    }

    @Test
    void removalFailureIsSwallowed() {
        when(userRepository.findById(inviterId)).thenReturn(Optional.empty());
        when(notificationDispatcher.messagesFor(inviteeId)).thenReturn(TestMessages.fr());
        doThrow(new IllegalStateException("fcm")).when(notificationDispatcher)
                .notifyUser(eq(inviteeId), any(), any(), any());

        assertThatCode(() -> listener.onInvitationRemoved(
                new RecipientInvitationRemovedEvent(invitationId, inviterId, inviteeId))).doesNotThrowAnyException();
    }

    @Test
    void failureIsSwallowed() {
        when(userRepository.findById(inviterId)).thenReturn(Optional.empty());
        when(notificationDispatcher.messagesFor(inviteeId)).thenReturn(TestMessages.fr());
        doThrow(new IllegalStateException("fcm")).when(notificationDispatcher)
                .notifyUser(eq(inviteeId), any(), any(), any());

        assertThatCode(() -> listener.onInvitationCreated(
                new RecipientInvitationCreatedEvent(invitationId, inviterId, inviteeId))).doesNotThrowAnyException();
    }
}

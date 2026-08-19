package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.dto.NotifyMessageRequest;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagingNotifyControllerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock ConversationService conversationService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock UserRepository userRepository;

    MessagingNotifyController controller;

    @BeforeEach
    void setUp() {
        controller = new MessagingNotifyController(
                conversationRepository, conversationService, notificationDispatcher, userRepository);
        ReflectionTestUtils.setField(controller, "internalSecret", "test-secret");
    }

    @Test
    void notify_returns401_whenSecretMissing() {
        var req = new NotifyMessageRequest("conv_1", "uid-1", "hello");
        assertThatThrownBy(() -> controller.notify(null, req))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                var e = (YadonyBusinessException) ex;
                assert e.getStatus().value() == 401;
            });
    }

    @Test
    void notify_returns401_whenSecretWrong() {
        var req = new NotifyMessageRequest("conv_1", "uid-1", "hello");
        assertThatThrownBy(() -> controller.notify("wrong-secret", req))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                var e = (YadonyBusinessException) ex;
                assert e.getStatus().value() == 401;
            });
    }

    @Test
    void notify_dispatchesNotification_whenValidSecret() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_bid1");
        when(conversationRepository.findByFirestoreConversationId("conv_bid1")).thenReturn(Optional.of(conv));

        controller.notify("test-secret",
            new NotifyMessageRequest("conv_bid1", "uid-sender", "Hello!"));

        verify(notificationDispatcher).sendMessageNotification(
            eq(senderId), eq(travelerId), eq("uid-sender"), eq("Hello!"), eq("conv_bid1"));
    }

    @Test
    void notify_returnsRecipientUid_soTheFunctionCanCreditUnreadWithoutFirestoreDoc() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_bid1");
        when(conversationRepository.findByFirestoreConversationId("conv_bid1")).thenReturn(Optional.of(conv));
        when(notificationDispatcher.sendMessageNotification(any(), any(), any(), any(), any()))
            .thenReturn("uid-recipient");

        var response = controller.notify("test-secret",
            new NotifyMessageRequest("conv_bid1", "uid-sender", "Hello!"));

        assert response.getStatusCode().value() == 200;
        assert "uid-recipient".equals(response.getBody().recipientFirebaseUid());
    }

    @Test
    void notify_repairsMissingFirestoreDocument() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_bid1");
        when(conversationRepository.findByFirestoreConversationId("conv_bid1")).thenReturn(Optional.of(conv));

        controller.notify("test-secret",
            new NotifyMessageRequest("conv_bid1", "uid-sender", "Hello!"));

        verify(conversationService).ensureFirestoreDocument(conv);
    }

    // ── Lot B : défense en profondeur — expéditeur muté ──────────────────────

    /**
     * Filet, pas le blocage principal (la règle Firestore l'est) : si un message
     * d'un expéditeur muté arrive quand même jusqu'à ce endpoint, on n'envoie pas
     * la notification au destinataire.
     */
    @Test
    void notify_skipsNotification_whenSenderIsMessagingMuted() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_bid1");
        when(conversationRepository.findByFirestoreConversationId("conv_bid1")).thenReturn(Optional.of(conv));

        UserEntity mutedSender = new UserEntity();
        setField(mutedSender, "messagingMutedUntil", Instant.now().plusSeconds(3600));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(mutedSender));

        var response = controller.notify("test-secret",
                new NotifyMessageRequest("conv_bid1", "uid-sender", "Hello!"));

        assert response.getStatusCode().value() == 200;
        verify(notificationDispatcher, never()).sendMessageNotification(any(), any(), any(), any(), any());
    }

    @Test
    void notify_sendsNotification_whenSenderMuteHasExpired() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_bid1");
        when(conversationRepository.findByFirestoreConversationId("conv_bid1")).thenReturn(Optional.of(conv));

        UserEntity previouslyMutedSender = new UserEntity();
        setField(previouslyMutedSender, "messagingMutedUntil", Instant.now().minusSeconds(3600));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(previouslyMutedSender));

        controller.notify("test-secret",
                new NotifyMessageRequest("conv_bid1", "uid-sender", "Hello!"));

        verify(notificationDispatcher).sendMessageNotification(
                eq(senderId), eq(travelerId), eq("uid-sender"), eq("Hello!"), eq("conv_bid1"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

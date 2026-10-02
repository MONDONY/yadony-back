package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.calls.CallStatus;
import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Push « Appel manqué » à l'appelé, sauf blocage ; rien pour un appel terminé ou refusé. */
@ExtendWith(MockitoExtension.class)
class CallMissedNotificationListenerTest {

    @Mock NotificationDispatcher dispatcher;
    @Mock UserRepository users;
    CallMissedNotificationListener listener;
    UUID caller = UUID.randomUUID();
    UUID callee = UUID.randomUUID();
    UUID conv = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CallMissedNotificationListener(dispatcher, users);
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
        UserEntity u = new UserEntity();
        u.setFirstName("Awa");
        lenient().when(users.findById(caller)).thenReturn(Optional.of(u));
    }

    private CallEndedEvent event(CallStatus s) {
        return new CallEndedEvent(UUID.randomUUID(), conv, "fs-1", caller, callee, s, null);
    }

    @Test
    void appelManquePousseALAppele() {
        listener.onCallEnded(event(CallStatus.MISSED));
        verify(dispatcher).notifyUnlessBlocked(eq(callee), eq(caller), eq("Appel manqué"),
                eq("Awa a essayé de vous appeler."),
                argThat(m -> "CALL_MISSED".equals(m.get("type")) && conv.toString().equals(m.get("conversationId"))));
    }

    @Test
    void appelTermineOuRefuseSansPush() {
        listener.onCallEnded(event(CallStatus.ENDED));
        listener.onCallEnded(event(CallStatus.REJECTED));
        verify(dispatcher, never()).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
    }

    @Test
    void echecDuPushNeLevePas() {
        doThrow(new RuntimeException("fcm")).when(dispatcher).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
        listener.onCallEnded(event(CallStatus.MISSED));
    }
}

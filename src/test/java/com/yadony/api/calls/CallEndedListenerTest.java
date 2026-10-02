package com.yadony.api.calls;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallEndedListenerTest {

    @Mock FirestoreService firestore;
    @Mock NotificationDispatcher dispatcher;
    @Mock UserRepository users;
    CallEndedListener listener;
    UUID caller = UUID.randomUUID();
    UUID callee = UUID.randomUUID();
    UUID conv = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CallEndedListener(firestore, dispatcher, users);
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
        UserEntity u = new UserEntity();
        u.setFirstName("Awa");
        lenient().when(users.findById(caller)).thenReturn(Optional.of(u));
    }

    private CallEndedEvent event(CallStatus s, Integer d) {
        return new CallEndedEvent(UUID.randomUUID(), conv, "fs-1", caller, callee, s, d);
    }

    @Test
    void appelTermineEcritLaDureeEnMinutes() {
        listener.onCallEnded(event(CallStatus.ENDED, 245));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel audio · 4 min");
        verifyNoMoreInteractions(firestore);
        verify(dispatcher, never()).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
    }

    @Test
    void appelCourtEnSecondes() {
        listener.onCallEnded(event(CallStatus.ENDED, 42));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel audio · 42 s");
    }

    @Test
    void appelManqueMessageEtPush() {
        listener.onCallEnded(event(CallStatus.MISSED, null));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel manqué");
        verify(dispatcher).notifyUnlessBlocked(eq(callee), eq(caller), eq("Appel manqué"),
                eq("Awa a essayé de vous appeler."),
                argThat(m -> "CALL_MISSED".equals(m.get("type")) && conv.toString().equals(m.get("conversationId"))));
    }

    @Test
    void appelRefuseSansPush() {
        listener.onCallEnded(event(CallStatus.REJECTED, null));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel refusé");
        verify(dispatcher, never()).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
    }

    @Test
    void enAnglaisPourUnAppeleAnglophone() {
        when(dispatcher.messagesFor(callee)).thenReturn(TestMessages.en());
        listener.onCallEnded(event(CallStatus.MISSED, null));
        verify(firestore).addSystemMessage("fs-1", "📞 Missed call");
        verify(dispatcher).notifyUnlessBlocked(eq(callee), eq(caller), eq("Missed call"),
                eq("Awa tried to call you."), anyMap());
    }

    @Test
    void echecFirestoreNEmpechePasLePush() {
        doThrow(new RuntimeException("down")).when(firestore).addSystemMessage(any(), any());
        listener.onCallEnded(event(CallStatus.MISSED, null));
        verify(dispatcher).notifyUnlessBlocked(eq(callee), eq(caller), any(), any(), anyMap());
    }

    @Test
    void echecDuPushNeLevePas() {
        doThrow(new RuntimeException("fcm")).when(dispatcher).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
        listener.onCallEnded(event(CallStatus.MISSED, null));
        verify(firestore).addSystemMessage(eq("fs-1"), any());
    }

    @Test
    void sansConversationFirestoreRienNEstEcrit() {
        listener.onCallEnded(new CallEndedEvent(UUID.randomUUID(), conv, null, caller, callee, CallStatus.ENDED, 10));
        verifyNoInteractions(firestore);
    }
}

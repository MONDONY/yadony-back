package com.yadony.api.messaging;

import com.yadony.api.calls.CallStatus;
import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Trace d'un appel dans la conversation, rédigée dans la langue de l'appelé. */
@ExtendWith(MockitoExtension.class)
class CallSystemMessageListenerTest {

    @Mock FirestoreService firestore;

    private CallSystemMessageListener listener(AppLanguage lang) {
        return new CallSystemMessageListener(firestore, TestMessages.resolver(lang));
    }

    private CallEndedEvent event(CallStatus s, Integer d) {
        return new CallEndedEvent(UUID.randomUUID(), UUID.randomUUID(), "fs-1", UUID.randomUUID(), UUID.randomUUID(), s, d);
    }

    @Test
    void appelTermineEnMinutes() {
        listener(AppLanguage.FR).onCallEnded(event(CallStatus.ENDED, 245));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel audio · 4 min");
    }

    @Test
    void appelCourtEnSecondes() {
        listener(AppLanguage.FR).onCallEnded(event(CallStatus.ENDED, 42));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel audio · 42 s");
    }

    @Test
    void appelTermineSansDureeConnue() {
        listener(AppLanguage.FR).onCallEnded(event(CallStatus.ENDED, null));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel audio · 0 s");
    }

    @Test
    void manqueEtRefuse() {
        listener(AppLanguage.FR).onCallEnded(event(CallStatus.MISSED, null));
        listener(AppLanguage.FR).onCallEnded(event(CallStatus.REJECTED, null));
        verify(firestore).addSystemMessage("fs-1", "📞 Appel manqué");
        verify(firestore).addSystemMessage("fs-1", "📞 Appel refusé");
    }

    @Test
    void enAnglais() {
        listener(AppLanguage.EN).onCallEnded(event(CallStatus.MISSED, null));
        verify(firestore).addSystemMessage("fs-1", "📞 Missed call");
    }

    @Test
    void echecFirestoreNeLevePas() {
        doThrow(new RuntimeException("down")).when(firestore).addSystemMessage(any(), any());
        listener(AppLanguage.FR).onCallEnded(event(CallStatus.MISSED, null));
    }

    @Test
    void sansConversationFirestoreRienNEstEcrit() {
        listener(AppLanguage.FR).onCallEnded(new CallEndedEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), UUID.randomUUID(), CallStatus.ENDED, 10));
        verifyNoInteractions(firestore);
    }
}

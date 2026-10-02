package com.yadony.api.calls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallWebhookServiceTest {

    @Mock CallRepository calls;
    @Mock ConversationRepository conversations;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;

    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-02T10:05:00Z");
    CallWebhookService service;
    CallEntity call;
    UUID caller = UUID.randomUUID();
    UUID callee = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CallTransitions transitions = new CallTransitions(calls, conversations, audit, events);
        service = new CallWebhookService(calls, transitions, Clock.fixed(now, ZoneOffset.UTC));
        UUID convId = UUID.randomUUID();
        call = new CallEntity(convId, UUID.randomUUID(), caller, callee, "c1");
        ReflectionTestUtils.setField(call, "id", UUID.randomUUID());
        ConversationEntity conv = new ConversationEntity(UUID.randomUUID(), caller, callee, "fs-1");
        lenient().when(calls.findByStreamCallId("c1")).thenReturn(Optional.of(call));
        lenient().when(conversations.findById(convId)).thenReturn(Optional.of(conv));
        lenient().when(calls.finishIfLive(any(), any(), any(), any(), any())).thenReturn(1);
        lenient().when(calls.markAnswered(any(), any())).thenReturn(1);
    }

    private void send(String body) throws Exception { service.handle(json.readTree(body)); }

    private CallEndedEvent publishedEvent() {
        ArgumentCaptor<CallEndedEvent> ev = ArgumentCaptor.forClass(CallEndedEvent.class);
        verify(events).publishEvent(ev.capture());
        return ev.getValue();
    }

    @Test
    void lAppeleDecrocheEnregistreLeDebut() throws Exception {
        send("""
             {"type":"call.session_participant_joined","call_cid":"audio_call:c1","created_at":"2026-10-02T10:00:00Z",
              "participant":{"user":{"id":"%s"}}}""".formatted(callee));
        verify(calls).markAnswered(call.getId(), OffsetDateTime.parse("2026-10-02T10:00:00Z"));
    }

    @Test
    void lAppelantQuiRejointNeDecrochePas() throws Exception {
        send("""
             {"type":"call.session_participant_joined","call_cid":"audio_call:c1","participant":{"user":{"id":"%s"}}}""".formatted(caller));
        verify(calls, never()).markAnswered(any(), any());
    }

    @Test
    void finDeSessionAvecAcceptedByEstUnAppelTermineMemeSansJoinRecu() throws Exception {
        send("""
             {"type":"call.session_ended","call_cid":"audio_call:c1","created_at":"2026-10-02T10:04:10Z",
              "call":{"session":{"accepted_by":{"%s":"2026-10-02T10:00:00Z"},"ended_at":"2026-10-02T10:04:05Z"}}}""".formatted(callee));
        verify(calls).finishIfLive(call.getId(), CallStatus.ENDED, OffsetDateTime.parse("2026-10-02T10:04:05Z"),
                OffsetDateTime.parse("2026-10-02T10:00:00Z"), 245);
        assertThat(publishedEvent().durationSeconds()).isEqualTo(245);
    }

    @Test
    void finDeSessionApresDecrocheEnregistreEstUnAppelTermine() throws Exception {
        call.answer(OffsetDateTime.parse("2026-10-02T10:00:00Z"));
        send("""
             {"type":"call.session_ended","call_cid":"audio_call:c1","created_at":"2026-10-02T10:01:00Z"}""");
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.ENDED), any(), any(), eq(60));
        assertThat(publishedEvent().firestoreConversationId()).isEqualTo("fs-1");
    }

    @Test
    void appelantQuiRaccrocheAvantDecrocheEstUnAppelManque() throws Exception {
        send("""
             {"type":"call.session_ended","call_cid":"audio_call:c1","call":{"session":{"accepted_by":{}}}}""");
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.MISSED), any(), isNull(), isNull());
    }

    @Test
    void manque() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1"}""");
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.MISSED), any(), isNull(), isNull());
        verify(audit).log(eq("CALL"), eq(call.getId()), eq("CALL_ENDED"), isNull(), anyMap());
    }

    @Test
    void lAppeleRefuse() throws Exception {
        send("""
             {"type":"call.rejected","call_cid":"audio_call:c1","user":{"id":"%s"},"reason":"decline"}""".formatted(callee));
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.REJECTED), any(), isNull(), isNull());
    }

    @Test
    void lAppelantAnnuleCEstUnAppelManquePasUnRefus() throws Exception {
        send("""
             {"type":"call.rejected","call_cid":"audio_call:c1","user":{"id":"%s"},"reason":"cancel"}""".formatted(caller));
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.MISSED), any(), isNull(), isNull());
    }

    @Test
    void sonnerieExpireeCoteAppeleEstUnAppelManque() throws Exception {
        send("""
             {"type":"call.rejected","call_cid":"audio_call:c1","user":{"id":"%s"},"reason":"timeout"}""".formatted(callee));
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.MISSED), any(), isNull(), isNull());
    }

    @Test
    void finDejaPriseParUnAutreWebhookNePubliePas() throws Exception {
        when(calls.finishIfLive(any(), any(), any(), any(), any())).thenReturn(0);
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1"}""");
        verifyNoInteractions(events);
        verifyNoInteractions(audit);
    }

    @Test
    void dateIllisibleRemplaceeParMaintenant() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1","created_at":"pas-une-date"}""");
        verify(calls).finishIfLive(eq(call.getId()), eq(CallStatus.MISSED), eq(OffsetDateTime.ofInstant(now, ZoneOffset.UTC)), any(), any());
    }

    @Test
    void autreTypeDAppelOuAppelInconnuIgnore() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"default:c1"}""");
        send("""
             {"type":"call.missed","call_cid":"audio_call:zzz"}""");
        send("""
             {"type":"call.created","call_cid":"audio_call:c1"}""");
        verify(calls, never()).finishIfLive(any(), any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void unManqueRecuApresLeDecrocheNeTransformePasLAppelEnManque() throws Exception {
        // Stream peut signaler l'appelant comme « manqué » ou l'annulation de la
        // sonnerie après le décroché : seule la fin de session conclut l'appel.
        ReflectionTestUtils.setField(call, "status", CallStatus.ANSWERED);
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1"}""");
        send("""
             {"type":"call.rejected","call_cid":"audio_call:c1","user":{"id":"%s"},"reason":"cancel"}""".formatted(caller));
        verify(calls, never()).finishIfLive(any(), any(), any(), any(), any());
    }
}

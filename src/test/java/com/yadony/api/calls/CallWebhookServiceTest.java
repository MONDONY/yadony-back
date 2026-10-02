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
        service = new CallWebhookService(calls, conversations, audit, events, Clock.fixed(now, ZoneOffset.UTC));
        UUID convId = UUID.randomUUID();
        call = new CallEntity(convId, UUID.randomUUID(), caller, callee, "c1");
        ReflectionTestUtils.setField(call, "id", UUID.randomUUID());
        ConversationEntity conv = new ConversationEntity(UUID.randomUUID(), caller, callee, "fs-1");
        lenient().when(calls.findByStreamCallId("c1")).thenReturn(Optional.of(call));
        lenient().when(conversations.findById(convId)).thenReturn(Optional.of(conv));
    }

    private void send(String body) throws Exception { service.handle(json.readTree(body)); }

    @Test
    void decrochePuisFinEnregistreLaDuree() throws Exception {
        send("""
             {"type":"call.session_participant_joined","call_cid":"audio_call:c1","created_at":"2026-10-02T10:00:00Z",
              "participant":{"user":{"id":"%s"}}}""".formatted(callee));
        send("""
             {"type":"call.session_ended","call_cid":"audio_call:c1","created_at":"2026-10-02T10:04:05Z"}""");
        assertThat(call.getStatus()).isEqualTo(CallStatus.ENDED);
        assertThat(call.getDurationSeconds()).isEqualTo(245);
        ArgumentCaptor<CallEndedEvent> ev = ArgumentCaptor.forClass(CallEndedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().firestoreConversationId()).isEqualTo("fs-1");
        assertThat(ev.getValue().status()).isEqualTo(CallStatus.ENDED);
        assertThat(ev.getValue().durationSeconds()).isEqualTo(245);
    }

    @Test
    void appelantQuiRaccrocheAvantDecrocheEstUnAppelManque() throws Exception {
        send("""
             {"type":"call.session_participant_joined","call_cid":"audio_call:c1","participant":{"user":{"id":"%s"}}}""".formatted(caller));
        send("""
             {"type":"call.session_ended","call_cid":"audio_call:c1"}""");
        assertThat(call.getStatus()).isEqualTo(CallStatus.MISSED);
        assertThat(call.getDurationSeconds()).isNull();
    }

    @Test
    void manque() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1"}""");
        assertThat(call.getStatus()).isEqualTo(CallStatus.MISSED);
        verify(audit).log(eq("CALL"), eq(call.getId()), eq("CALL_ENDED"), isNull(), anyMap());
    }

    @Test
    void refuse() throws Exception {
        send("""
             {"type":"call.rejected","call_cid":"audio_call:c1","user":{"id":"%s"}}""".formatted(callee));
        assertThat(call.getStatus()).isEqualTo(CallStatus.REJECTED);
    }

    @Test
    void webhookRejoueNePublieQuUneFois() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1"}""");
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1"}""");
        send("""
             {"type":"call.session_ended","call_cid":"audio_call:c1"}""");
        verify(events, times(1)).publishEvent(any(CallEndedEvent.class));
        assertThat(call.getStatus()).isEqualTo(CallStatus.MISSED);
    }

    @Test
    void dateIllisibleRemplaceeParMaintenant() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"audio_call:c1","created_at":"pas-une-date"}""");
        assertThat(call.getEndedAt().toInstant()).isEqualTo(now);
    }

    @Test
    void autreTypeDAppelOuAppelInconnuIgnore() throws Exception {
        send("""
             {"type":"call.missed","call_cid":"default:c1"}""");
        send("""
             {"type":"call.missed","call_cid":"audio_call:zzz"}""");
        send("""
             {"type":"call.created","call_cid":"audio_call:c1"}""");
        assertThat(call.getStatus()).isEqualTo(CallStatus.RINGING);
        verifyNoInteractions(events);
    }
}

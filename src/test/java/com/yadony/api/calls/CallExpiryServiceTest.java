package com.yadony.api.calls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Un appel que Stream n'a jamais soldé (webhook perdu) ne doit pas bloquer la conversation. */
@ExtendWith(MockitoExtension.class)
class CallExpiryServiceTest {

    @Mock CallRepository calls;
    @Mock CallTransitions transitions;

    static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    final OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    CallExpiryService service;

    @BeforeEach
    void setUp() {
        service = new CallExpiryService(calls, transitions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CallEntity call(LocalDateTime createdAt) {
        CallEntity c = new CallEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "s");
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(c, "createdAt", createdAt);
        return c;
    }

    @Test
    void sonnerieDePlusDe2MinutesDevientManquee() {
        CallEntity stale = call(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(3));
        when(calls.findByStatusIn(anyCollection())).thenReturn(List.of(stale));
        service.expireStale();
        verify(transitions).finish(stale, CallStatus.MISSED, now, null);
    }

    @Test
    void sonnerieRecenteIntacte() {
        CallEntity fresh = call(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(30));
        when(calls.findByStatusIn(anyCollection())).thenReturn(List.of(fresh));
        service.expireStale();
        verifyNoInteractions(transitions);
    }

    @Test
    void conversationDeplusDUneHeureTermineeALaDureeMaximale() {
        CallEntity live = call(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(80));
        OffsetDateTime started = now.minusMinutes(79);
        live.answer(started);
        when(calls.findByStatusIn(anyCollection())).thenReturn(List.of(live));
        service.expireStale();
        verify(transitions).finish(live, CallStatus.ENDED, started.plusSeconds(3600), started);
    }

    @Test
    void conversationEnCoursIntacte() {
        CallEntity live = call(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(20));
        live.answer(now.minusMinutes(19));
        when(calls.findByStatusIn(anyCollection())).thenReturn(List.of(live));
        service.expireStale();
        verifyNoInteractions(transitions);
    }

    @Test
    void expirationCibleeSurUneConversation() {
        UUID conv = UUID.randomUUID();
        CallEntity stale = call(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(5));
        when(calls.findByConversationIdAndStatusIn(eq(conv), anyCollection())).thenReturn(List.of(stale));
        service.expireStale(conv);
        verify(transitions).finish(stale, CallStatus.MISSED, now, null);
    }
}

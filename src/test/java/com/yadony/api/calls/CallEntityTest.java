package com.yadony.api.calls;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CallEntityTest {

    private CallEntity newCall() {
        return new CallEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "abc");
    }

    @Test
    void nouvelAppelSonne() {
        assertThat(newCall().getStatus()).isEqualTo(CallStatus.RINGING);
    }

    @Test
    void decrocherPuisTerminerCalculeLaDuree() {
        CallEntity call = newCall();
        OffsetDateTime t0 = OffsetDateTime.of(2026, 10, 2, 10, 0, 0, 0, ZoneOffset.UTC);
        call.answer(t0);
        assertThat(call.finish(CallStatus.ENDED, t0.plusSeconds(245))).isTrue();
        assertThat(call.getStatus()).isEqualTo(CallStatus.ENDED);
        assertThat(call.getDurationSeconds()).isEqualTo(245);
    }

    @Test
    void finDejaTerminaleEstIgnoree() {
        CallEntity call = newCall();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(call.finish(CallStatus.MISSED, now)).isTrue();
        assertThat(call.finish(CallStatus.ENDED, now.plusSeconds(5))).isFalse();
        assertThat(call.getStatus()).isEqualTo(CallStatus.MISSED);
        assertThat(call.getDurationSeconds()).isNull();
    }

    @Test
    void finSansDecrocheResteSansDuree() {
        CallEntity call = newCall();
        call.finish(CallStatus.REJECTED, OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(call.getDurationSeconds()).isNull();
        assertThat(call.getStartedAt()).isNull();
    }

    @Test
    void decrocherApresFinEstSansEffet() {
        CallEntity call = newCall();
        call.finish(CallStatus.MISSED, OffsetDateTime.now(ZoneOffset.UTC));
        call.answer(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(call.getStatus()).isEqualTo(CallStatus.MISSED);
    }
}

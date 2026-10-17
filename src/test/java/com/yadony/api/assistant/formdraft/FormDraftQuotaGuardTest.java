package com.yadony.api.assistant.formdraft;

import com.yadony.api.assistant.AssistantProperties;
import com.yadony.api.assistant.AssistantQuotaGuard;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormDraftQuotaGuardTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T22:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final FormDraftQuotaGuard guard = new FormDraftQuotaGuard(new FormDraftProperties(true, 2, 8), clock);

    @Test
    void decompteEtRefuseAuDela() {
        assertThat(guard.consume("u1")).isEqualTo(1);
        assertThat(guard.consume("u1")).isZero();
        assertThatThrownBy(() -> guard.consume("u1")).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getErrorCode()).isEqualTo("assistant-form-draft-quota-exceeded");
            assertThat(e.getProperties()).containsEntry("retryAfter", "2026-10-18T00:00:00Z");
        });
    }

    @Test
    void repartAZeroLeLendemainUtc() {
        guard.consume("u1");
        guard.consume("u1");
        now.set(Instant.parse("2026-10-18T00:00:01Z"));
        assertThat(guard.consume("u1")).isEqualTo(1);
    }

    @Test
    void separeDuQuotaDeLAssistant() {
        AssistantQuotaGuard assistant = new AssistantQuotaGuard(new AssistantProperties(true, 1, 6.5), clock);
        assistant.consume("u1");
        assertThat(guard.consume("u1")).isEqualTo(1);
    }
}

package com.yadony.api.assistant.parcel;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantLookupGuardTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));
    private final AssistantLookupGuard guard = new AssistantLookupGuard(new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    });

    @Test
    void bloqueApresDixEchecsDansLaJourneePuisRepartLeLendemain() {
        for (int i = 0; i < AssistantLookupGuard.MAX_FAILED_LOOKUPS_PER_DAY - 1; i++) guard.recordFailure("uid");
        assertThat(guard.blocked("uid")).isFalse();
        guard.recordFailure("uid");
        assertThat(guard.blocked("uid")).isTrue();
        assertThat(guard.blocked("autre")).isFalse();

        now.set(Instant.parse("2026-10-18T00:00:01Z"));
        assertThat(guard.blocked("uid")).isFalse();
    }
}

package com.yadony.api.addressbook.invitation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LookupRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void allowsThirtyThenRefusesPerUser() {
        LookupRateLimiter limiter = new LookupRateLimiter(clock, 30);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            assertThat(limiter.tryAcquire(user)).as("call %d", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire(user)).isFalse();
        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
    }

    @Test
    void windowSlidesAfterOneHour() {
        LookupRateLimiter limiter = new LookupRateLimiter(clock, 30);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire(user);
        }
        now.set(now.get().plus(Duration.ofMinutes(59)));
        assertThat(limiter.tryAcquire(user)).isFalse();
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertThat(limiter.tryAcquire(user)).isTrue();
    }

    @Test
    void limitIsParametrisedAndBucketsAreIndependent() {
        LookupRateLimiter lookup = new LookupRateLimiter(clock, 30);
        LookupRateLimiter availability = new LookupRateLimiter(clock, 120);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            lookup.tryAcquire(user);
        }
        assertThat(lookup.tryAcquire(user)).isFalse();
        for (int i = 0; i < 120; i++) {
            assertThat(availability.tryAcquire(user)).as("call %d", i + 1).isTrue();
        }
        assertThat(availability.tryAcquire(user)).isFalse();
    }
}

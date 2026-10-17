package com.yadony.api.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlidingWindowRateLimiter")
class SlidingWindowRateLimiterTest {

    /** Horloge réglable : chaque test avance le temps à la main. */
    private static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long millis) { this.millis = millis; }
        void advance(Duration d) { millis += d.toMillis(); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    @DisplayName("laisse passer jusqu'à la limite, refuse au-delà, clés indépendantes")
    void allowsUpToLimit() {
        MutableClock clock = new MutableClock(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, MINUTE);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a", 3, MINUTE)).isEmpty();
        }
        assertThat(limiter.tryAcquire("a", 3, MINUTE)).isPresent();
        assertThat(limiter.tryAcquire("b", 3, MINUTE)).isEmpty();
    }

    @Test
    @DisplayName("fenêtre pleine dès le début : Retry-After couvre la fin de fenêtre + la décroissance")
    void retryAfterWhenCurrentWindowFull() {
        MutableClock clock = new MutableClock(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, MINUTE);
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("k", 4, MINUTE);
        }
        clock.advance(Duration.ofSeconds(10));

        // 50 s jusqu'à la fenêtre suivante, puis 4 × (1 - t/60) + 1 ≤ 4 → t ≥ 15 s.
        assertThat(limiter.tryAcquire("k", 4, MINUTE)).hasValue(65);

        // Le délai annoncé suffit : à cet instant la requête passe.
        clock.advance(Duration.ofSeconds(65));
        assertThat(limiter.tryAcquire("k", 4, MINUTE)).isEmpty();
    }

    @Test
    @DisplayName("la fenêtre précédente pèse au prorata : pas de double débit à la frontière")
    void previousWindowIsWeighted() {
        MutableClock clock = new MutableClock(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, MINUTE);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k", 10, MINUTE);
        }
        // Juste après la frontière : la précédente compte encore presque entière.
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("k", 10, MINUTE)).isPresent();

        // À mi-fenêtre : 10 × 0,5 = 5 → encore 5 places.
        clock.advance(Duration.ofSeconds(29));
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire("k", 10, MINUTE)).isEmpty();
        }
        // estimation 5 + 4 = 9 → la 5e passe (9 + 1 ≤ 10), la 6e non.
        assertThat(limiter.tryAcquire("k", 10, MINUTE)).isEmpty();
        assertThat(limiter.tryAcquire("k", 10, MINUTE)).isPresent();
    }

    @Test
    @DisplayName("refus dû à la fenêtre précédente : Retry-After borné à la fin de la fenêtre en cours")
    void retryAfterBoundedByWindowEnd() {
        MutableClock clock = new MutableClock(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, MINUTE);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k", 10, MINUTE);
        }
        clock.advance(Duration.ofSeconds(61));

        // 10 × (1 - (1 + x)/60) + 0 + 1 ≤ 10 → x ≥ 5 s.
        assertThat(limiter.tryAcquire("k", 10, MINUTE)).hasValue(5);
    }

    @Test
    @DisplayName("au-delà de deux fenêtres, le compteur repart de zéro")
    void forgetsAfterTwoWindows() {
        MutableClock clock = new MutableClock(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, MINUTE);
        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire("k", 2, MINUTE);
        }
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(limiter.tryAcquire("k", 2, MINUTE)).isEmpty();
        assertThat(limiter.tryAcquire("k", 2, MINUTE)).isEmpty();
    }
}

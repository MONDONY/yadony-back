package com.yadony.api.smsotp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmsOtpNumberTracker — numéros visés par compte sur 24 h")
class SmsOtpNumberTrackerTest {

    /** Horloge que le test fait avancer. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T10:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final SmsOtpNumberTracker tracker = new SmsOtpNumberTracker(clock);

    @Test
    @DisplayName("compte les numéros différents, pas les renvois du même numéro")
    void countsDistinctNumbers() {
        assertThat(tracker.record("uid-a", "+2250100000001")).isEqualTo(1);
        assertThat(tracker.record("uid-a", "+2250100000001")).isEqualTo(1);
        assertThat(tracker.record("uid-a", "+2250100000002")).isEqualTo(2);
        assertThat(tracker.record("uid-a", "+2250100000003")).isEqualTo(3);
    }

    @Test
    @DisplayName("chaque compte a son propre compteur")
    void perAccount() {
        tracker.record("uid-a", "+2250100000001");
        tracker.record("uid-a", "+2250100000002");

        assertThat(tracker.record("uid-b", "+2250100000003")).isEqualTo(1);
    }

    @Test
    @DisplayName("un numéro visé il y a plus de 24 h ne compte plus")
    void slidingWindow() {
        tracker.record("uid-a", "+2250100000001");
        clock.advance(Duration.ofHours(23));
        tracker.record("uid-a", "+2250100000002");
        clock.advance(Duration.ofHours(2));

        // Le premier numéro a 25 h : sorti de la fenêtre ; le deuxième a 2 h.
        assertThat(tracker.record("uid-a", "+2250100000003")).isEqualTo(2);
    }

    @Test
    @DisplayName("oublier un compte (suspendu) le fait repartir de zéro, sans toucher aux autres")
    void forgetResetsOnlyThatAccount() {
        tracker.record("uid-a", "+2250100000001");
        tracker.record("uid-a", "+2250100000002");
        tracker.record("uid-b", "+2250100000009");

        tracker.forget("uid-a");

        assertThat(tracker.record("uid-a", "+2250100000003")).isEqualTo(1);
        assertThat(tracker.record("uid-b", "+2250100000008")).isEqualTo(2);
    }

    @Test
    @DisplayName("redemander un numéro rafraîchit sa date")
    void reRequestRefreshesTimestamp() {
        tracker.record("uid-a", "+2250100000001");
        clock.advance(Duration.ofHours(20));
        tracker.record("uid-a", "+2250100000001");
        clock.advance(Duration.ofHours(10));

        assertThat(tracker.record("uid-a", "+2250100000002")).isEqualTo(2);
    }
}

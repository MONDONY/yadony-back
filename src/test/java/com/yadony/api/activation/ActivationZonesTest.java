package com.yadony.api.activation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationZonesTest {

    @Test
    void knownAndDefaultZones() {
        assertThat(ActivationZones.zoneFor("SN")).isEqualTo(ZoneId.of("Africa/Dakar"));
        assertThat(ActivationZones.zoneFor(" cm ")).isEqualTo(ZoneId.of("Africa/Douala"));
        assertThat(ActivationZones.zoneFor("FR")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(ActivationZones.zoneFor(null)).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void window_isLocalTime() {
        Instant noonUtc = Instant.parse("2026-10-02T12:00:00Z");
        assertThat(ActivationZones.isWithinWindow("SN", noonUtc, 10, 20)).isTrue();
        Instant lateUtc = Instant.parse("2026-10-02T20:30:00Z");
        assertThat(ActivationZones.isWithinWindow("SN", lateUtc, 10, 20)).isFalse();   // 20h30 à Dakar
        Instant earlyUtc = Instant.parse("2026-10-02T08:30:00Z");
        assertThat(ActivationZones.isWithinWindow("FR", earlyUtc, 10, 20)).isTrue();   // 10h30 à Paris (UTC+2)
        assertThat(ActivationZones.isWithinWindow("SN", earlyUtc, 10, 20)).isFalse();  // 8h30 à Dakar
    }
}

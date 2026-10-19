package com.yadony.api.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class UserZonesTest {

    @Test
    void knownAndDefaultZones() {
        assertThat(UserZones.zoneFor("SN")).isEqualTo(ZoneId.of("Africa/Dakar"));
        assertThat(UserZones.zoneFor(" cm ")).isEqualTo(ZoneId.of("Africa/Douala"));
        assertThat(UserZones.zoneFor("FR")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(UserZones.zoneFor(null)).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void window_isLocalTime() {
        Instant noonUtc = Instant.parse("2026-10-02T12:00:00Z");
        assertThat(UserZones.isWithinWindow("SN", noonUtc, 10, 20)).isTrue();
        Instant lateUtc = Instant.parse("2026-10-02T20:30:00Z");
        assertThat(UserZones.isWithinWindow("SN", lateUtc, 10, 20)).isFalse();   // 20h30 à Dakar
        Instant earlyUtc = Instant.parse("2026-10-02T08:30:00Z");
        assertThat(UserZones.isWithinWindow("FR", earlyUtc, 10, 20)).isTrue();   // 10h30 à Paris (UTC+2)
        assertThat(UserZones.isWithinWindow("SN", earlyUtc, 10, 20)).isFalse();  // 8h30 à Dakar
    }
}

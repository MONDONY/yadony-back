package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementEntityHandoverDeadlineTest {

    // 30/09/2026 10:00 UTC = 12:00 à Paris (UTC+2) = 10:00 à Abidjan (UTC+0)
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private static AnnouncementEntity announcement(String timezone, LocalDateTime deadline) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTimezone(timezone);
        a.setHandoverDeadline(deadline);
        return a;
    }

    @Test
    @DisplayName("sans date limite → jamais dépassée")
    void noDeadline_notPassed() {
        assertThat(announcement("Europe/Paris", null).isHandoverDeadlinePassed(NOW)).isFalse();
    }

    @Test
    @DisplayName("date limite la veille → dépassée")
    void deadlineYesterday_passed() {
        assertThat(announcement("Africa/Abidjan", LocalDateTime.of(2026, 9, 29, 18, 0))
                .isHandoverDeadlinePassed(NOW)).isTrue();
    }

    @Test
    @DisplayName("date limite demain → pas dépassée")
    void deadlineTomorrow_notPassed() {
        assertThat(announcement("Africa/Abidjan", LocalDateTime.of(2026, 10, 1, 18, 0))
                .isHandoverDeadlinePassed(NOW)).isFalse();
    }

    @Test
    @DisplayName("heure murale lue dans le fuseau du trajet, pas en UTC")
    void deadlineReadInTripTimezone() {
        LocalDateTime elevenAm = LocalDateTime.of(2026, 9, 30, 11, 0);
        // 11:00 à Paris = 09:00 UTC → dépassée ; 11:00 à Abidjan = 11:00 UTC → pas encore
        assertThat(announcement("Europe/Paris", elevenAm).isHandoverDeadlinePassed(NOW)).isTrue();
        assertThat(announcement("Africa/Abidjan", elevenAm).isHandoverDeadlinePassed(NOW)).isFalse();
    }

    @Test
    @DisplayName("date limite exactement maintenant → dépassée")
    void deadlineExactlyNow_passed() {
        assertThat(announcement("Africa/Abidjan", LocalDateTime.of(2026, 9, 30, 10, 0))
                .isHandoverDeadlinePassed(NOW)).isTrue();
    }

    @Test
    @DisplayName("fuseau vide → repli Europe/Paris")
    void blankTimezone_fallsBackToParis() {
        assertThat(announcement(" ", LocalDateTime.of(2026, 9, 30, 11, 0))
                .isHandoverDeadlinePassed(NOW)).isTrue();
    }
}
